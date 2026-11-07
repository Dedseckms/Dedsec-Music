# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
from email.message import Message
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT));sys.path.insert(0,str(ROOT/'api'))
import audio
import profile_api as profile
import server
import kit
import start

def headers(**values):
    result=Message()
    for key,value in values.items():result[key.replace('_','-')]=str(value)
    return result

class AuthTests(unittest.TestCase):
    def test_untrusted_jwt_hint_requires_native_identity_match(self):
        import base64
        payload=base64.urlsafe_b64encode(json.dumps({'uid':'native-id','sub':'user'}).encode()).decode().rstrip('=')
        token='header.'+payload+'.signature'
        with patch.object(profile,'native',return_value=({'id':'native-id','userName':'user'},{})) as native:
            result=profile.authenticate(headers(Authorization='Bearer '+token))
            self.assertEqual(result['id'],'native-id');native.assert_called_once_with(token,'/api/user/native-id')
        with patch.object(profile,'native',return_value=({'id':'other','userName':'user'},{})):
            with self.assertRaises(profile.ProfileError):profile.authenticate(headers(Authorization='Bearer '+token))

    def test_upload_login_decodes_password_and_returns_verified_native_jwt(self):
        response=types.SimpleNamespace(read=lambda count:json.dumps({'token':'a.b.c'}).encode())
        with patch.object(profile.HTTP,'open',return_value=contextlib.nullcontext(response)) as request, \
                patch.object(profile,'authenticate',return_value={'id':'user'}),patch.object(server,'login_allowed',return_value=True):
            self.assertEqual(server.login('username=listener',headers(X_Password='p%2Bass%20word%25')),b'a.b.c')
            self.assertEqual(json.loads(request.call_args.args[0].data)['password'],'p+ass word%')

    def test_login_rate_bound_and_query_allowlist(self):
        with server.LOGIN_LOCK:server.LOGIN_ATTEMPTS.clear()
        self.assertTrue(all(server.login_allowed(1) for _ in range(20)))
        self.assertFalse(server.login_allowed(1));self.assertTrue(server.login_allowed(62))
        with self.assertRaises(profile.ProfileError):server.login('username=u&admin=true',headers(X_Password='secret'))

class AudioTests(unittest.TestCase):
    def probe(self):return {'format':{'format_name':'mp3','duration':'1200'},'streams':[{'codec_type':'audio'}]}
    def test_real_audio_and_attached_cover_allowed_video_and_invalid_duration_rejected(self):
        valid=self.probe();valid['streams'].append({'codec_type':'video','disposition':{'attached_pic':1}})
        self.assertEqual(audio.check_probe(valid),1200)
        for duration in ['1200.001','nan','inf','0','-1']:
            value=self.probe();value['format']['duration']=duration
            with self.assertRaises(audio.Rejected):audio.check_probe(value)
        value=self.probe();value['streams'].append({'codec_type':'video'})
        with self.assertRaises(audio.Rejected):audio.check_probe(value)
        value=self.probe();value['streams']=[]
        with self.assertRaises(audio.Rejected):audio.check_probe(value)

    def test_actual_byte_count_and_low_disk_never_publish(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);stage=root/'stage';publish=root/'library';stage.mkdir();publish.mkdir()
            with patch.object(audio,'safe_directory',return_value=10),patch.object(audio.os,'close'), \
                    patch.object(audio,'MAX_BYTES',5),patch.object(audio,'publish_file') as publication:
                with self.assertRaises(audio.Rejected) as failure:
                    audio.receive([b'123',b'456'],stage,publish,'file.mp3',disk_usage=lambda p:types.SimpleNamespace(free=audio.RESERVE+100))
                self.assertEqual(failure.exception.status,413);publication.assert_not_called()
                self.assertFalse(list(stage.iterdir()));self.assertFalse(list(publish.iterdir()))
            with patch.object(audio,'publish_file') as publication:
                with self.assertRaises(audio.Rejected) as failure:
                    audio.receive([b'1'],stage,publish,'file.mp3',disk_usage=lambda p:types.SimpleNamespace(free=0))
                self.assertEqual(failure.exception.status,507);publication.assert_not_called()

    def test_unknown_length_chunked_is_counted_and_ambiguous_headers_rejected(self):
        self.assertEqual(b''.join(audio.body_chunks(io.BytesIO(b'3\r\nabc\r\n0\r\n\r\n'),headers(Transfer_Encoding='chunked'))),b'abc')
        with self.assertRaises(audio.Rejected):list(audio.body_chunks(io.BytesIO(),headers(Content_Length=3,Transfer_Encoding='chunked')))
        duplicate=headers(Content_Length=3);duplicate['Content-Length']='3'
        with self.assertRaises(audio.Rejected):list(audio.body_chunks(io.BytesIO(b'abc'),duplicate))

    def test_traversal_and_overwrite_denied(self):
        for query in ['source=Uploads&path=/../file.mp3','source=Other&path=/file.mp3','source=Uploads&path=/file.mp3&override=true','source=Uploads&path=/dir/file.mp3']:
            with self.assertRaises(audio.Rejected):audio.filename(query)

    @unittest.skipUnless(os.name=='posix','Unix dirfd publication')
    def test_atomic_publication_never_overwrites_existing(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'pending';source.write_bytes(b'new');destination=root/'library';destination.mkdir()
            with patch.object(audio.shutil,'disk_usage',return_value=types.SimpleNamespace(free=audio.RESERVE+100)):
                audio.publish_file(source,destination,'same.mp3',validator=lambda p:1,identifier='user-upload')
                other=root/'other';other.write_bytes(b'changed')
                with self.assertRaises(audio.Rejected) as failure:audio.publish_file(other,destination,'same.mp3',validator=lambda p:1,identifier='user-upload')
            self.assertEqual(failure.exception.status,409);self.assertEqual((destination/'user-upload/same.mp3').read_bytes(),b'new')

    def test_every_user_reauthenticates_and_uses_uuid_scope(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def receive(chunks,stage,folder,name,validator):
                validator(Path('fake-audio'));self.assertNotIn('owner',str(folder));self.assertEqual(name,'original.mp3');return 10,1
            with patch.object(server,'LIBRARY',root),patch.object(audio,'safe_directory',return_value=3),patch.object(audio.os,'close'), \
                    patch.object(audio,'receive',side_effect=receive),patch.object(audio,'validate',return_value=1), \
                    patch.object(profile,'native',return_value=({'id':'owner'},{})) as verification:
                result=server.upload({'id':'owner','token':'private'},'source=Uploads&path=/original.mp3&override=false',[b'audio'])
            self.assertEqual(result['bytes'],10);verification.assert_called_once_with('private','/api/user/owner')

class SetupTests(unittest.TestCase):
    def test_owner_failure_does_not_publish_proxy(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);calls=[]
            response=contextlib.nullcontext(types.SimpleNamespace(status=200))
            with patch.object(kit,'ROOT',root),patch.object(start.os,'name','posix'),patch.object(start.sys.stdin,'isatty',return_value=True), \
                    patch.object(kit,'config',return_value={'LOCAL_UID':'1000','LOCAL_GID':'1000'}), \
                    patch.object(start.os,'getuid',return_value=1000,create=True),patch.object(start.os,'getgid',return_value=1000,create=True), \
                    patch.object(start.subprocess,'run'),patch.object(kit,'project_guard'),patch.object(kit,'free_port'), \
                    patch.object(kit,'compose',side_effect=lambda *a,**kw:(calls.append(a) or types.SimpleNamespace(stdout=''))), \
                    patch.object(start.shutil,'disk_usage',return_value=types.SimpleNamespace(free=20*2**30)), \
                    patch.object(kit.HTTP,'open',return_value=response),patch.object(start,'initialize_owner',side_effect=RuntimeError('not verified')):
                with self.assertRaises(RuntimeError):start.main()
            self.assertFalse((root/'data/bootstrap/owner.ready').exists())
            self.assertTrue(all('caddy' not in call for call in calls))

    def test_foreign_compose_project_is_never_reused(self):
        with patch.object(kit,'config',return_value={'STACK_NAME':'chosen'}),patch.object(kit.subprocess,'run',return_value=types.SimpleNamespace(stdout='/some/other/project\n')):
            with self.assertRaises(RuntimeError):kit.project_guard()

    def test_sqlite_online_backup_is_consistent_and_source_retained(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);data=root/'data/navidrome';data.mkdir(parents=True);folder=root/'backup';folder.mkdir()
            with contextlib.closing(sqlite3.connect(data/'navidrome.db')) as db:
                db.execute('CREATE TABLE example(id INTEGER)');db.execute('INSERT INTO example VALUES(42)');db.commit()
            with patch.object(kit,'ROOT',root):checksum=kit.backup_db(folder)
            self.assertEqual(len(checksum),64)
            with contextlib.closing(sqlite3.connect(folder/'navidrome.sqlite')) as copy:self.assertEqual(copy.execute('SELECT id FROM example').fetchone()[0],42)
            self.assertTrue((data/'navidrome.db').is_file())

if __name__=='__main__':unittest.main()
