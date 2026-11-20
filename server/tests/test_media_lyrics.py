# SPDX-License-Identifier: GPL-3.0-or-later
import collections
import contextlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import types
import unittest
import urllib.error
from email.message import Message
from unittest.mock import Mock,patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'api'))
import profile_api as profile
import admin_api
import audio
import media_api as media
import lyrics_api as lyrics
from radio_catalog import Catalog


class LyricsTests(unittest.TestCase):
    def fixture(self,root,native=None,external=None):
        library=root/'music';library.mkdir();(library/'song.mp3').write_bytes(b'fixture audio')
        db=root/'nav.sqlite'
        with contextlib.closing(sqlite3.connect(db)) as d:
            d.executescript('''CREATE TABLE media_file(id TEXT,library_id INTEGER,path TEXT,title TEXT,artist TEXT,album TEXT,duration REAL,updated_at TEXT,missing INTEGER);
              CREATE TABLE user_library(user_id TEXT,library_id INTEGER);
              INSERT INTO media_file VALUES('song',1,'song.mp3','A Song (Live)','An Artist','An Album',180,'version1',0);
              INSERT INTO user_library VALUES('owner',1);''')
        manager=lyrics.Manager(root/'state',Catalog(db,{'1':library}),start=False,native=native or Mock(return_value=None),
                               external=external or Mock(return_value=lyrics.response('not_found')))
        return manager,library,db,{'id':'owner','username':'Fixture','token':'memory-only-native-token'}

    def finish(self,manager):
        item=manager.work.get_nowait();manager.process(item);manager.pending.discard(item[2]);manager.work.task_done()

    def synced_fixture(self,root):
        native=Mock(return_value=lyrics.response('ready',[{'startMs':1000,'text':'Original source line'}],True,'Navidrome'))
        m,lib,db,user=self.fixture(root,native);m.protected_owner='owner'
        m.get(user,'song');self.finish(m)
        return m,lib,db,user,native

    def test_owner_timing_persists_across_restart_refresh_and_shared_reads(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);m,lib,db,user,native=self.synced_fixture(root)
            original=m.get(user,'song');self.assertTrue(original['canEditTiming'])
            saved=m.set_timing(user,'song',750,original['revision'])
            self.assertEqual(saved['timingOffsetMs'],750);self.assertEqual(saved['lines'],original['lines'])
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute("INSERT INTO user_library VALUES('friend',1)")
            friend={**user,'id':'friend','isAdmin':True}
            value=m.get(friend,'song');self.assertFalse(value['canEditTiming']);self.assertEqual(value['timingOffsetMs'],750)
            m.close()
            m=lyrics.Manager(root/'state',Catalog(db,{'1':lib}),start=False,native=native,protected_owner='owner')
            self.assertEqual(m.get(user,'song')['timingOffsetMs'],750)
            with m.db() as d:d.execute('UPDATE cache SET expires=0')
            self.assertEqual(m.get(user,'song')['status'],'pending');self.finish(m)
            self.assertEqual(m.get(user,'song')['timingOffsetMs'],750)
            native.return_value=lyrics.response('ready',[{'startMs':2000,'text':'New timing'}],True,'Navidrome')
            with m.db() as d:d.execute('UPDATE cache SET expires=0')
            m.get(user,'song');self.finish(m)
            self.assertEqual(m.get(user,'song')['timingOffsetMs'],0)
            with self.assertRaises(profile.ProfileError) as error:m.set_timing(user,'song',500,original['revision'])
            self.assertEqual(error.exception.status,409)

    def test_timing_failclosed_owner_visibility_strict_bounds_and_stale_source(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user,native=self.synced_fixture(Path(tmp));revision=m.get(user,'song')['revision']
            for who in ({**user,'id':'stranger'},{**user,'id':'admin','isAdmin':True}):
                with self.assertRaises(profile.ProfileError) as error:m.set_timing(who,'song',0,revision)
                self.assertEqual(error.exception.status,403)
            m.protected_owner=None
            self.assertFalse(m.get(user,'song')['canEditTiming'])
            with self.assertRaises(profile.ProfileError) as error:m.set_timing(user,'song',0,revision)
            self.assertEqual(error.exception.status,403);m.protected_owner='owner'
            for invalid in (True,1.0,'250',10001,-10001,None):
                with self.assertRaises(profile.ProfileError) as error:m.set_timing(user,'song',invalid,revision)
                self.assertEqual(error.exception.status,400)
            for offset in (-10000,10000,0):self.assertEqual(m.set_timing(user,'song',offset,revision)['timingOffsetMs'],offset)
            m.guard=Mock(side_effect=profile.ProfileError(401))
            with self.assertRaises(profile.ProfileError) as error:m.set_timing(user,'song',0,revision)
            self.assertEqual(error.exception.status,401);m.guard=lambda who:None
            (lib/'song.mp3').write_bytes(b'replacement recording')
            with self.assertRaises(profile.ProfileError) as error:m.set_timing(user,'song',250,revision)
            self.assertEqual(error.exception.status,409)
            m.get(user,'song');self.finish(m)
            self.assertEqual(m.get(user,'song')['timingOffsetMs'],0)
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute('DELETE FROM user_library')
            for action in (lambda:m.get(user,'song'),lambda:m.set_timing(user,'song',0,revision)):
                with self.assertRaises(profile.ProfileError) as error:action()
                self.assertEqual(error.exception.status,404)

    def test_pending_get_does_not_perform_network_and_cache_checks_visibility_first(self):
        with tempfile.TemporaryDirectory() as tmp:
            native=Mock(return_value=lyrics.response('ready',[{'startMs':None,'text':'Real source words'}],False,'Navidrome'))
            m,lib,db,user=self.fixture(Path(tmp),native)
            self.assertEqual(m.get(user,'song')['status'],'pending');native.assert_not_called()
            self.assertEqual(m.get(user,'song')['status'],'pending');self.assertEqual(m.work.qsize(),1)
            self.finish(m);cached=m.get(user,'song');self.assertEqual(cached['source'],'Navidrome');self.assertEqual(len(cached['revision']),64)
            self.assertEqual(native.call_count,1)
            with self.assertRaises(profile.ProfileError):m.get({**user,'id':'other'},'song')
            m.guard=Mock(side_effect=profile.ProfileError(401))
            with self.assertRaises(profile.ProfileError):m.get(user,'song')

    def test_file_replacement_invalidates_cache_and_queued_version_never_publishes(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp));m.get(user,'song');(lib/'song.mp3').write_bytes(b'new audio version')
            self.finish(m)
            with m.db() as d:self.assertEqual(d.execute('SELECT count(*) FROM cache').fetchone()[0],0)
            self.assertEqual(m.get(user,'song')['status'],'pending');self.finish(m)
            self.assertEqual(m.get(user,'song')['status'],'not_found')
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute("UPDATE media_file SET title='Different version',updated_at='version2'")
            self.assertEqual(m.get(user,'song')['status'],'pending')

    def test_exact_provider_metadata_duration_plain_and_instrumental(self):
        song={'title':'A Song (Live)','artist':'An Artist','album':'An Album','duration':180}
        value={'trackName':song['title'],'artistName':song['artist'],'albumName':song['album'],'duration':181,'plainLyrics':'line one\nline two'}
        result=lyrics.lrclib_result(value,song);self.assertFalse(result['synced']);self.assertTrue(all(x['startMs'] is None for x in result['lines']))
        for change in ({'trackName':'A Song'},{'albumName':'Other Album'},{'artistName':'Another Artist'},{'duration':183},{'duration':float('nan')}):
            self.assertEqual(lyrics.lrclib_result({**value,**change},song)['status'],'not_found')
        self.assertEqual(lyrics.lrclib_result({**value,'instrumental':True},song)['status'],'instrumental')

    def test_lrc_keeps_source_timestamps_repeats_and_offset_no_interpolation(self):
        result=lyrics.timed_lines('[offset:-100]\n[00:01.1][00:03.123]same\n[00:02.12]next',180)
        self.assertEqual([x['startMs'] for x in result],[1000,2020,3023]);self.assertEqual([x['text'] for x in result],['same','next','same'])
        self.assertEqual(lyrics.timed_lines('plain text without times',180),[])
        with self.assertRaises(ValueError):lyrics.timed_lines('[99:59]outside',180)

    def test_album_fallback_requires_unique_exact_signature_and_keeps_version_qualifiers(self):
        song={'title':'A Song (Live)','artist':'An Artist','album':'','duration':180}
        candidate={'trackName':song['title'],'artistName':song['artist'],'albumName':'Original Album','duration':180,'plainLyrics':'Actual source text'}
        self.assertEqual(lyrics.search_result([candidate],song)['status'],'ready')
        self.assertEqual(lyrics.search_result([candidate,{**candidate,'albumName':'Another recording'}],song)['status'],'not_found')
        self.assertEqual(lyrics.search_result([{**candidate,'trackName':'A Song'}],song)['status'],'not_found')
        confirmed={**song,'album':'Original Album'}
        self.assertEqual(lyrics.search_result([candidate,{**candidate,'albumName':'Other'}],confirmed)['status'],'ready')
        with patch.object(lyrics,'bounded_json',return_value=[candidate]) as fetch:
            self.assertEqual(lyrics.external_lyrics(song)['status'],'ready');self.assertIn('/search?',fetch.call_args.args[1].full_url)

    def test_native_structured_payload_preserves_synced_and_plain_semantics(self):
        song={'duration':180}
        root={'subsonic-response':{'status':'ok','lyricsList':{'structuredLyrics':[{'synced':True,'offset':-100,'line':[{'start':1000,'value':'Native line'}]}]}}}
        value=lyrics.embedded_result(root,song);self.assertEqual(value['lines'][0]['startMs'],900);self.assertEqual(value['source'],'Navidrome')
        root['subsonic-response']['lyricsList']['structuredLyrics'][0]['synced']=False
        self.assertIsNone(lyrics.embedded_result(root,song)['lines'][0]['startMs'])
        root['subsonic-response']={'status':'failed','error':{'code':40}}
        with self.assertRaises(profile.ProfileError):lyrics.embedded_result(root,song)

    def test_negative_cache_error_expiration_and_explicit_retry_after(self):
        with tempfile.TemporaryDirectory() as tmp:
            headers=Message();headers['Retry-After']='1800'
            external=Mock(side_effect=urllib.error.HTTPError('redacted',429,'rate',headers,None))
            m,lib,db,user=self.fixture(Path(tmp),external=external);m.get(user,'song');self.finish(m)
            self.assertEqual(m.get(user,'song')['status'],'error');self.assertGreater(m.external_retry,lyrics.time.time()+1700)
            with m.db() as d:d.execute('UPDATE cache SET expires=0')
            m.get(user,'song');self.finish(m);self.assertEqual(external.call_count,1)
            with m.db() as d:d.execute('UPDATE cache SET expires=0')
            m.external_retry=0;external.side_effect=None;external.return_value=lyrics.response('not_found')
            m.get(user,'song');self.finish(m);self.assertEqual(m.get(user,'song')['status'],'not_found')
            with m.db() as d:self.assertNotIn(user['token'],''.join(str(row) for row in d.execute('SELECT * FROM cache')))

    def test_lyrics_response_body_and_line_counts_are_bounded(self):
        incoming=types.SimpleNamespace(read=lambda n:b'x'*n)
        class Context:
            def __enter__(self):return incoming
            def __exit__(self,*args):pass
        with self.assertRaises(ValueError):lyrics.bounded_json(types.SimpleNamespace(open=lambda *a,**k:Context()),object())
        with self.assertRaises(ValueError):lyrics.timed_lines('\n'.join('[00:01]x' for _ in range(2001)),180)
        self.assertIsNone(profile.NoRedirect().redirect_request(None,None,None,None,None,'https://other.example'))
        with self.assertRaises(ValueError):lyrics.embedded_result({'subsonic-response':{'status':'ok','lyricsList':None}},{'duration':10})

    def test_expired_job_credentials_do_not_poison_other_authorized_users_cache(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp),native=Mock(side_effect=profile.ProfileError(401)))
            m.get(user,'song');self.finish(m)
            with m.db() as d:self.assertEqual(d.execute('SELECT count(*) FROM cache').fetchone()[0],0)
            self.assertEqual(m.get(user,'song')['status'],'pending')

    def test_external_request_never_contains_native_auth_and_native_auth_is_body_only(self):
        song={'id':'song','title':'A','artist':'B','album':'C','duration':180};user={'id':'u','username':'Fixture','token':'secret-native'}
        def examine(opener,request,timeout=4):
            if request.full_url.startswith(lyrics.LRCLIB):
                self.assertIsNone(request.get_header('Authorization'));self.assertNotIn(user['token'],request.full_url)
                return {'trackName':'A','artistName':'B','albumName':'C','duration':180,'instrumental':True}
            self.assertEqual(request.full_url,profile.NAV+'/rest/getLyricsBySongId.view')
            self.assertNotIn(user['token'],request.full_url);self.assertIn(b'jwt=secret-native',request.data)
            return {'subsonic-response':{'status':'ok','lyricsList':{'structuredLyrics':[]}}}
        with patch.object(lyrics,'bounded_json',examine):
            self.assertIsNone(lyrics.native_lyrics(user,song));self.assertEqual(lyrics.external_lyrics(song)['status'],'instrumental')

    def test_global_queue_and_per_user_poll_bounds(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp))
            original=m.song
            def distinct(uid,key):
                song,version=original(uid,'song');return {**song,'id':key},version+key
            m.song=distinct
            for n in range(32):self.assertEqual(m.get(user,str(n))['status'],'pending')
            self.assertEqual(m.get(user,'overflow')['status'],'error');self.assertEqual(m.work.qsize(),32)
            for _ in range(27):m.get(user,'0')
            with self.assertRaises(profile.ProfileError):m.get(user,'0')
            m.close();self.assertEqual(m.work.qsize(),0)


class MediaTests(unittest.TestCase):
    def request(self,path,method='GET',headers=None,body=b''):
        h=types.SimpleNamespace(path=profile.PREFIX+path,command=method,headers=headers or Message(),rfile=io.BytesIO(body));values=[]
        with patch.object(profile,'send',side_effect=lambda handler,status,value:values.append((status,value))):
            code=profile.dispatch(h,audio.body_chunks)
        return code,values[0][1]

    def test_timing_route_auth_body_limit_and_fixed_song_route(self):
        manager=Mock();manager.set_timing.return_value={'timingOffsetMs':250,'canEditTiming':True}
        identity={'id':'owner','username':'Fixture'}
        body=json.dumps({'offsetMs':250,'revision':'a'*64}).encode()
        headers=Message();headers['Content-Length']=str(len(body))
        with patch.object(profile,'authenticate',side_effect=profile.ProfileError(401)):
            self.assertEqual(self.request('/lyrics/song/timing','POST',headers,body)[0],401)
        with patch.object(profile,'authenticate',return_value=identity),patch.object(media,'UPLOAD',object()),patch.object(media,'LYRICS',manager),patch.object(admin_api,'SERVICE',None):
            self.assertEqual(self.request('/lyrics/song/timing','POST',headers,body)[0],200)
            manager.set_timing.assert_called_once_with(identity,'song',250,'a'*64)
            manager.set_timing.reset_mock()
            for invalid in (b'{}',b'[]',b'null',b'{invalid'):
                h=Message();h['Content-Length']=str(len(invalid))
                self.assertEqual(self.request('/lyrics/song/timing','POST',h,invalid)[0],400)
            h=Message();h['Content-Length']='1025'
            self.assertEqual(self.request('/lyrics/song/timing','POST',h,b'x'*1025)[0],413)
            self.assertNotEqual(self.request('/lyrics/song/timing?override=true','POST',headers,body)[0],200)
            manager.set_timing.assert_not_called()

    def test_capability_auth_same_jwt_upload_and_legacy_callback_reuse(self):
        upload=Mock(return_value={'status':'accepted','name':'a.mp3','bytes':3,'duration':1});identity={'id':'u','token':'same-jwt','username':'Fixture'}
        with patch.object(profile,'authenticate',return_value=identity),patch.object(media,'UPLOAD',upload),patch.object(media,'LYRICS',object()),patch.object(admin_api,'SERVICE',None):
            status,value=self.request('/capabilities');self.assertEqual(status,200);self.assertEqual(value['uploads'],{'mode':'navidrome','path':profile.PREFIX+'/uploads'})
            headers=Message();headers['Content-Length']='3';status,value=self.request('/uploads?path=/a.mp3','POST',headers,b'abc')
            self.assertEqual(status,200);self.assertEqual(upload.call_args.args[0]['token'],'same-jwt')
            self.assertEqual(b''.join(upload.call_args.args[2]),b'abc');self.assertIn('source=Uploads',upload.call_args.args[1])

    def test_rejection_auth_oversize_override_traversal_and_revoked_user(self):
        with patch.object(profile,'authenticate',side_effect=profile.ProfileError(401)):
            self.assertEqual(self.request('/capabilities')[0],401)
        with patch.object(profile,'authenticate',return_value={'id':'u','username':'Fixture'}),patch.object(media,'UPLOAD',Mock()),patch.object(media,'LYRICS',object()),patch.object(admin_api,'SERVICE',None):
            headers=Message();headers['Content-Length']=str(audio.MAX_BYTES+1)
            self.assertEqual(self.request('/uploads?path=/a.mp3','POST',headers)[0],413)
            self.assertEqual(self.request('/uploads?path=/a.mp3&override=true','POST')[0],400)
            self.assertEqual(self.request('/uploads?path=/../a.mp3','POST')[0],415)
            self.assertNotEqual(self.request('/lyrics/..%2fsecret')[0],200)
        policy=types.SimpleNamespace(native_blocked=lambda *a:True)
        with patch.object(profile,'authenticate',return_value={'id':'u','username':'Fixture'}),patch.object(admin_api,'SERVICE',policy):
            self.assertEqual(self.request('/capabilities')[0],401)


if __name__=='__main__':unittest.main()
