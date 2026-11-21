# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
from email.message import Message
import json
import importlib.util
import os
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import Mock,patch

import test_lyrics_batch as fixtures
import test_media_lyrics as media_fixtures
import lyrics_api as lyrics
import lyrics_alignment as alignment
import profile_api as profile
import media_api as media


class AlignmentTests(unittest.TestCase):
    def fixture(self,root):
        m,lib,db,user=fixtures.BatchTests().fixture(root)
        with contextlib.closing(sqlite3.connect(db)) as d:
            with d:
                d.execute('UPDATE media_file SET duration=2')
                d.execute("INSERT INTO user_library VALUES('friend',1)")
        value=lyrics.response('ready',[{'startMs':None,'text':text} for text in ('Привет мир','','Снова музыка')],False,'LRCLIB')
        m.native=Mock(return_value=value);m.get(user,'song');media_fixtures.LyricsTests().finish(m)
        _,version=m.song('owner','song')
        with m.db() as d:d.execute("INSERT INTO lyrics_batch_song VALUES(?,?,'plain',1)",('song',version))
        m.batch.control_trusted('owner','align_start');m.alignment.discover()
        return m,lib,db,user,version,value

    def lease(self,m):return m.alignment.next(extractor=lambda song:b'\0'*64000)

    def result(self,job,**changes):
        return {**{'lease':job['lease'],'model':alignment.MODEL,'status':'aligned','startsMs':[100,1000],'coverage':1},**changes}

    def test_alignment_is_optin_single_durable_lease_with_opaque_binary_audio(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));broker=m.alignment
            job=self.lease(m);self.assertIsNotNone(job);self.assertEqual(job['lines'],['Привет мир','Снова музыка'])
            self.assertIs(type(job['expiresAt']),int)
            with m.db() as d:self.assertEqual(d.execute('SELECT expires FROM lyrics_align_job').fetchone()[0],job['expiresAt'])
            encoded=json.dumps(job);self.assertNotIn('song.mp3',encoded);self.assertNotIn(user['token'],encoded)
            self.assertNotIn('owner',encoded);self.assertNotIn('Fixture',encoded)
            self.assertIsNone(self.lease(m))
            n=lyrics.Manager(Path(tmp)/'state',m.catalog,start=False,protected_owner='owner',background_guard=lambda identity:None)
            self.assertIsNone(self.lease(n))
            with patch.object(alignment.stat,'S_IMODE',return_value=0o600):stream,size=n.alignment.audio(job['lease'])
            with stream:self.assertEqual(len(stream.read()),64000)
            self.assertEqual(size,64000);self.assertEqual(n.alignment.status()['active'],1)
            n.batch.control_trusted('owner','align_pause')
            self.assertFalse(n.alignment.status()['enabled']);self.assertEqual(n.alignment.status()['active'],0)
            with self.assertRaises(profile.ProfileError):broker.audio(job['lease'])

    def test_success_preserves_original_words_shared_reads_and_survives_source_cache_refresh(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));job=self.lease(m)
            self.assertEqual(m.alignment.complete(self.result(job)),{'ok':True})
            result=m.get({**user,'id':'friend'},'song')
            self.assertTrue(result['synced']);self.assertEqual(result['source'],'Automatic alignment (MFA)')
            self.assertEqual([line['text'] for line in result['lines']],job['lines'])
            self.assertNotEqual(result['revision'],value['revision'])
            self.assertEqual(m.batch.status(user)['alignment']['done'],1)
            with m.db() as d:
                self.assertEqual(d.execute('SELECT status FROM lyrics_batch_song').fetchone()[0],'synced')
                d.execute('DELETE FROM cache')
            self.assertTrue(m.get(user,'song')['synced'])
            self.assertTrue(m.alignment.restore(version,value)['synced'])
            self.assertEqual(m.set_timing(user,'song',250,result['revision'])['timingOffsetMs'],250)
            with self.assertRaises(profile.ProfileError):m.alignment.complete(self.result(job))

    def test_result_rejects_partial_nonfinite_bad_order_and_wrong_model(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));job=self.lease(m)
            invalids=({'coverage':.9},{'coverage':True},{'coverage':float('nan')},{'startsMs':[100]},
                      {'startsMs':[1000,100]},{'startsMs':[-1,100]},{'startsMs':[100,2001]},
                      {'startsMs':[100.0,1000]},{'model':'other'},{'startsMs':[None,1000]})
            for change in invalids:
                with self.assertRaises(ValueError):m.alignment.complete(self.result(job,**change))
            self.assertFalse(m.get(user,'song')['synced'])
            self.assertEqual(m.alignment.status()['active'],1)

    def test_replaced_audio_stale_revision_manual_override_and_revoked_owner_never_publish(self):
        for change in ('audio','revision','manual','revoked','visibility'):
            with self.subTest(change=change),tempfile.TemporaryDirectory() as tmp:
                m,lib,db,user,version,value=self.fixture(Path(tmp));job=self.lease(m)
                if change=='audio':(lib/'song.mp3').write_bytes(b'replaced')
                elif change=='revision':
                    with m.db() as d:d.execute('UPDATE cache SET value=?',(json.dumps(lyrics.response('ready',[{'startMs':None,'text':'Другой текст'}],False,'LRCLIB')),))
                elif change=='manual':
                    with m.db() as d:d.execute('INSERT INTO timing VALUES(?,?,?,?,0)',('song',version,value['revision'],250))
                elif change=='revoked':m.background_guard=Mock(side_effect=profile.ProfileError(401))
                else:
                    with contextlib.closing(sqlite3.connect(db)) as d:
                        with d:d.execute("DELETE FROM user_library WHERE user_id='owner'")
                with self.assertRaises(profile.ProfileError):m.alignment.complete(self.result(job))
                with m.db() as d:
                    self.assertNotEqual(d.execute('SELECT status FROM lyrics_align_job').fetchone()[0],'done')
                    cached=d.execute('SELECT value FROM cache WHERE key=?',(version,)).fetchone()
                self.assertFalse(json.loads(cached[0])['synced'])
                self.assertEqual(m.alignment.status()['active'],0)

    def test_expiry_backoff_unsupported_and_restart_pause(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));job=self.lease(m)
            with m.db() as d:d.execute('UPDATE lyrics_align_job SET expires=0')
            m.alignment.recover();self.assertEqual(m.alignment.status()['errors'],1)
            self.assertIsNone(self.lease(m))
            with m.db() as d:
                d.execute('UPDATE lyrics_align_job SET retry=0');d.execute('UPDATE lyrics_align_control SET last_lease=0')
            job=self.lease(m)
            m.alignment.complete({'lease':job['lease'],'model':alignment.MODEL,'status':'unsupported','reason':'OOV'})
            self.assertEqual(m.alignment.status()['skipped'],1)
            self.assertFalse(m.get(user,'song')['synced'])
            m.batch.control_trusted('owner','align_pause')
            n=lyrics.Manager(Path(tmp)/'state',m.catalog,start=False,protected_owner='owner',background_guard=lambda identity:None)
            self.assertFalse(n.alignment.status()['enabled'])

    def test_three_expired_attempts_are_terminal_and_busy_decoder_does_not_consume_attempt(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp))
            m.alignment.slot=Mock();m.alignment.slot.acquire.return_value=False
            self.assertIsNone(self.lease(m))
            with m.db() as d:self.assertEqual(d.execute('SELECT attempts FROM lyrics_align_job').fetchone()[0],0)
            m.alignment.slot=None
            for attempt in range(3):
                with m.db() as d:
                    d.execute('UPDATE lyrics_align_control SET last_lease=0');d.execute('UPDATE lyrics_align_job SET retry=0')
                job=self.lease(m);self.assertIsNotNone(job)
                with m.db() as d:d.execute('UPDATE lyrics_align_job SET expires=0')
                m.alignment.recover()
            with m.db() as d:
                d.execute('UPDATE lyrics_align_control SET last_lease=0');d.execute('UPDATE lyrics_align_job SET retry=0')
            self.assertIsNone(self.lease(m));self.assertEqual(m.alignment.status()['errors'],1)

    def test_worker_routes_have_separate_auth_without_bypassing_native_routes(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));request=media_fixtures.MediaTests().request
            headers=Message();headers['Content-Length']='2'
            with patch.object(media,'LYRICS',m),patch.object(media,'UPLOAD',object()),patch.object(profile,'authenticate',side_effect=AssertionError('Worker must not need Nav JWT')):
                with patch.object(m.alignment,'authorize',return_value=False):
                    self.assertEqual(request('/lyrics-align/worker/lease','POST',headers,b'{}')[0],401)
                with patch.object(m.alignment,'authorize',return_value=True):
                    self.assertEqual(request('/lyrics-align/worker/status','POST',headers,b'{}')[0],200)
                    self.assertEqual(request('/lyrics-align/worker/unknown','POST',headers,b'{}')[0],400)
            with patch.object(profile,'authenticate',side_effect=profile.ProfileError(401)):
                self.assertEqual(request('/capabilities')[0],401)

    def test_result_storage_limit_pauses_without_touching_plain_lyrics(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));job=self.lease(m)
            with patch.object(alignment,'MAX_RESULT_BYTES',1):
                with self.assertRaises(profile.ProfileError) as error:m.alignment.complete(self.result(job))
            self.assertEqual(error.exception.status,507)
            self.assertFalse(m.alignment.status()['enabled']);self.assertEqual(m.alignment.status()['active'],0)
            self.assertFalse(m.get(user,'song')['synced'])

    @unittest.skipUnless(os.environ.get('LYRICS_ALIGN_WORKER_PATH'),'optional native worker contract integration')
    def test_real_broker_lease_validates_in_portable_worker(self):
        path=Path(os.environ['LYRICS_ALIGN_WORKER_PATH'])
        spec=importlib.util.spec_from_file_location('lyrics_alignment_worker_contract',path)
        worker=importlib.util.module_from_spec(spec);spec.loader.exec_module(worker)
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp))
            job=json.loads(json.dumps(self.lease(m)))
            worker.validate_job(job)

    @unittest.skipIf(os.name!='posix','POSIX file permission contract')
    def test_dedicated_token_permissions_scope_duplicate_headers_and_no_native_jwt(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,version,value=self.fixture(Path(tmp));broker=m.alignment
            broker.token_path.write_text('a'*64);broker.token_path.chmod(0o600)
            headers=Message();headers['Authorization']='Bearer '+'a'*64
            self.assertTrue(broker.authorize(headers));headers['Authorization']='Bearer '+'a'*64
            self.assertFalse(broker.authorize(headers))
            broker.token_path.chmod(0o644);self.assertFalse(broker.authorize({'Authorization':'Bearer '+'a'*64}))
            broker.token_path.chmod(0o600);self.assertFalse(broker.authorize({'Authorization':'Bearer '+user['token']}))


if __name__=='__main__':unittest.main()
