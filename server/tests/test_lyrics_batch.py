# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
from email.message import Message
from pathlib import Path
import sqlite3
import tempfile
import unittest
import urllib.error
from unittest.mock import Mock
from unittest.mock import patch

import test_media_lyrics as fixtures
import lyrics_api as lyrics
import profile_api as profile


class BatchTests(unittest.TestCase):
    def fixture(self,root):
        m,lib,db,user=fixtures.LyricsTests().fixture(root)
        m.protected_owner='owner';m.background_guard=lambda identity:None
        return m,lib,db,user

    def steps(self,m,count=1):
        for _ in range(count):
            with m.db() as d:d.execute('UPDATE lyrics_batch_control SET next_fetch=0')
            m.external_retry=0;m.batch.step()

    def test_shared_cached_results_and_manual_timing_survive_resume(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);m,lib,db,user=self.fixture(root)
            m.native=Mock(return_value=lyrics.response('ready',[{'startMs':1000,'text':'Original'}],True,'Navidrome'))
            m.get(user,'song');fixtures.LyricsTests().finish(m)
            revision=m.get(user,'song')['revision'];m.set_timing(user,'song',500,revision)
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:
                    d.execute("INSERT INTO media_file SELECT 'second',library_id,path,'Second',artist,album,duration,updated_at,missing FROM media_file WHERE id='song'")
                    d.execute("INSERT INTO user_library VALUES('friend',1)")
            m.external=Mock(return_value=lyrics.response('ready',[{'startMs':None,'text':'Plain internet text'}],False,'LRCLIB'))
            self.assertEqual(m.batch.control(user,'start')['total'],2)
            self.steps(m,2);self.assertFalse(m.batch.status(user)['discovering'])
            self.steps(m)
            self.assertEqual(m.batch.status(user)['checked'],1)
            m.batch.control(user,'pause');self.assertFalse(m.batch.status(user)['running']);m.close()
            n=lyrics.Manager(root/'state',m.catalog,start=False,external=m.external,protected_owner='owner',background_guard=lambda identity:None)
            self.assertFalse(n.batch.status(user)['running'])
            n.batch.control(user,'start');self.steps(n,4)
            status=n.batch.status(user)
            self.assertEqual((status['checked'],status['synced'],status['plain'],status['pending']),(2,1,1,0))
            self.assertFalse(status['running']);self.assertEqual(m.external.call_count,1)
            self.assertEqual(n.get({**user,'id':'friend'},'song')['timingOffsetMs'],500)
            self.assertEqual(n.get({**user,'id':'friend'},'second')['lines'][0]['text'],'Plain internet text')
            with n.db() as d:
                stored=str(d.execute('SELECT * FROM lyrics_batch_control').fetchall())+str(d.execute('SELECT * FROM lyrics_batch_song').fetchall())
                self.assertNotIn(user['token'],stored)

    def test_owner_only_scope_and_replaced_physical_versions(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp))
            for who in ({**user,'id':'friend'},{**user,'id':'admin','isAdmin':True}):
                for call in (lambda:m.batch.status(who),lambda:m.batch.control(who,'start')):
                    with self.assertRaises(profile.ProfileError) as error:call()
                    self.assertEqual(error.exception.status,403)
            m.protected_owner=None
            with self.assertRaises(profile.ProfileError):m.batch.control(user,'start')
            m.protected_owner='owner'
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute("INSERT INTO media_file SELECT 'hidden',2,path,title,artist,album,duration,updated_at,missing FROM media_file WHERE id='song'")
            m.batch.control(user,'start');self.steps(m,3)
            self.assertEqual(m.batch.status(user)['total'],1);self.assertEqual(m.batch.status(user)['notFound'],1)
            (lib/'song.mp3').write_bytes(b'replaced recording')
            m.external=Mock(return_value=lyrics.response('instrumental',source='LRCLIB'))
            m.batch.control(user,'start');self.steps(m,3)
            self.assertEqual(m.batch.status(user)['instrumental'],1)
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute("DELETE FROM user_library WHERE user_id='owner'")
            m.batch.control(user,'start');self.steps(m)
            self.assertEqual(m.batch.status(user)['total'],0)

    def test_rate_limit_backoff_pause_and_restart_are_durable(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);m,lib,db,user=self.fixture(root)
            headers=Message();headers['Retry-After']='600'
            m.external=Mock(side_effect=urllib.error.HTTPError('redacted',429,'rate',headers,None))
            m.batch.control(user,'start');self.steps(m,3)
            self.assertEqual(m.batch.status(user)['checked'],0);self.assertEqual(m.external.call_count,1)
            self.assertGreater(m.batch.row()[5],lyrics.time.time()+590)
            m.batch.step();self.assertEqual(m.external.call_count,1)
            n=lyrics.Manager(root/'state',m.catalog,start=False,external=m.external,protected_owner='owner',background_guard=lambda identity:None)
            self.assertTrue(n.batch.status(user)['running']);n.batch.step();self.assertEqual(m.external.call_count,1)
            n.batch.control(user,'pause');self.steps(n);self.assertEqual(m.external.call_count,1)
            n.batch.control(user,'start');self.steps(n,2)
            m.external.side_effect=None;m.external.return_value=lyrics.response('not_found')
            self.steps(n);self.assertEqual(n.batch.status(user)['notFound'],1)
            self.assertGreater(n.batch.row()[5],lyrics.time.time()+1)

    def test_sidecar_priority_and_foreground_queue_priority(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp));(lib/'song.lrc').write_text('[00:01.00]Real sidecar words',encoding='utf-8')
            m.external=Mock(side_effect=AssertionError('Sidecar must avoid external fetch'))
            m.batch.control(user,'start');self.steps(m,3)
            value=m.get(user,'song');self.assertTrue(value['synced']);self.assertEqual(value['source'],'LRC')
            m.batch.step=Mock(side_effect=AssertionError('Foreground must run first'))
            m.work.put(({},None,None,None))
            def process(item):m.stop.set()
            m.process=process;m.loop();m.batch.step.assert_not_called()

    def test_external_search_fallback_paces_requests_and_cache_bytes_are_exact(self):
        song={'title':'Title','artist':'Artist','album':'Album','duration':100}
        def fetch(opener,request):
            if '/get?' in request.full_url:raise urllib.error.HTTPError('redacted',404,'absent',None,None)
            return []
        with patch.object(lyrics,'bounded_json',side_effect=fetch),patch.object(lyrics.time,'sleep') as pause:
            self.assertEqual(lyrics.external_lyrics(song,pace=2)['status'],'not_found')
            pause.assert_called_once_with(2)
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp))
            with m.db() as d:
                for value in ('русский текст','обновлённый текст'):
                    d.execute('INSERT INTO cache VALUES(?,?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',('fixture',value,1,1))
                    self.assertEqual(d.execute('SELECT items,bytes FROM cache_size').fetchone(),(1,len(value.encode())))
                d.execute('DELETE FROM cache')
                self.assertEqual(d.execute('SELECT items,bytes FROM cache_size').fetchone(),(0,0))

    def test_production_jwt_guard_is_never_used_for_background_or_local_bootstrap(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp))
            calls=[]
            def jwt_guard(identity):
                self.assertEqual(identity['token'],user['token']);calls.append('native')
            def background_guard(identity):
                self.assertNotIn('token',identity);self.assertEqual(identity['username'],'Fixture')
            m.guard=jwt_guard;m.background_guard=background_guard
            m.batch.control_trusted('owner','start');self.steps(m,3)
            self.assertEqual(calls,[])
            self.assertEqual(m.batch.status(user)['notFound'],1);self.assertEqual(calls,['native'])
            m.batch.control_trusted('owner','start')
            m.background_guard=Mock(side_effect=profile.ProfileError(401))
            self.steps(m);self.assertFalse(m.batch.status(user)['running'])
            with self.assertRaises(profile.ProfileError):m.batch.control_trusted('owner','start')
            m.background_guard=None
            with self.assertRaises(profile.ProfileError):m.batch.control_trusted('owner','start')
            m.background_guard=background_guard
            with contextlib.closing(sqlite3.connect(db)) as d:
                with d:d.execute("DELETE FROM user WHERE id='owner'")
            with self.assertRaises(profile.ProfileError):m.batch.control_trusted('owner','start')

    def test_owner_revocation_during_provider_request_does_not_publish(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,db,user=self.fixture(Path(tmp));revoked=[False]
            def guard(identity):
                if revoked[0]:raise profile.ProfileError(401)
            def external(song):
                revoked[0]=True
                return lyrics.response('ready',[{'startMs':1000,'text':'Must not publish'}],True,'LRCLIB')
            m.background_guard=guard;m.external=external
            m.batch.control_trusted('owner','start');self.steps(m,3)
            self.assertFalse(m.batch.status(user)['running'])
            self.assertEqual(m.batch.status(user)['checked'],0)
            with m.db() as d:self.assertEqual(d.execute('SELECT count(*) FROM cache').fetchone()[0],0)


if __name__=='__main__':unittest.main()
