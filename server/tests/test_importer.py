# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'api'))
import yandex_import as jobs
import yandex_source as source
from import_errors import ImportFailure

IDENTITY={'id':'user-a','token':'native-private'}
TOKEN='yandex-private-token-never-persisted'

class FastEvent:
    def __init__(self):self.value=False
    def set(self):self.value=True
    def is_set(self):return self.value
    def wait(self,seconds):return self.value

def meta(key,kind='music',available=True):
    return {'id':key,'kind':kind,'title':'Track '+key,'artist':'Artist','album':'Album',
        'albumArtist':'Artist','genre':'Rock','durationMs':1000,'available':available}

class FakeProvider:
    calls=[]
    failure=None
    cancel_on_download=False
    def __init__(self,token,event):
        if token=='bad-token':raise ImportFailure('AUTH_REQUIRED')
        self.account_hash='same-account';self.event=event
    def inventory(self):return iter(['1','1','2','3'])
    def metadata(self,ids):return {key:meta(key,'excluded' if key=='3' else 'music') for key in ids}
    def download(self,key,path):
        self.calls.append(key)
        if self.failure and key=='1':raise ImportFailure(self.failure,unavailable=self.failure=='SOURCE_UNAVAILABLE')
        path.write_bytes(b'audio-'+key.encode())
        if self.cancel_on_download:self.event.set()
        return '.mp3',meta(key)
    def close(self):pass

class ImportTests(unittest.TestCase):
    @contextlib.contextmanager
    def manager(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);FakeProvider.calls=[];FakeProvider.failure=None;FakeProvider.cancel_on_download=False
            m=jobs.Manager(root/'state',root/'storage',provider_factory=FakeProvider,start_worker=False,
                publisher=lambda src,dst:os.link(src,dst))
            with patch.object(jobs.audio,'validate',return_value=1),patch.object(source,'tag'), \
                    patch.object(source,'check_space'),patch.object(jobs.profile,'native',side_effect=lambda token,path:({'id':path.rsplit('/',1)[-1]},{})):
                try:yield m
                finally:m.close()

    def schedule(self,m,identity=IDENTITY,token=TOKEN,key=None):
        result=m.schedule(identity,token,key);m.events[result['id']]=FastEvent();return result['id']

    def test_unique_music_only_counters_and_global_cross_user_skip(self):
        with self.manager() as m:
            first=self.schedule(m);m.run_job(first);result=m.view('user-a',first)
            self.assertEqual((result['state'],result['discovered'],result['total'],result['processed'],result['downloaded'],result['excluded']),('completed',3,2,2,2,1))
            second=self.schedule(m,{'id':'user-b','token':'native-second'});m.run_job(second);result=m.view('user-b',second)
            self.assertEqual((result['downloaded'],result['skipped'],result['processed']),(0,2,2))
            self.assertEqual(FakeProvider.calls,['1','2'])
            self.assertNotIn(TOKEN,m.path.read_bytes().decode(errors='ignore'))
            for file in m.root.iterdir():
                if file.is_file():self.assertNotIn(TOKEN.encode(),file.read_bytes())
            self.assertFalse(m.credentials)

    def test_auth_failure_pauses_and_explicit_resume_does_not_double_count(self):
        with self.manager() as m:
            key=self.schedule(m,token='bad-token');m.run_job(key)
            self.assertEqual(m.view('user-a',key)['state'],'paused_auth')
            self.schedule(m,key=key);m.run_job(key);result=m.view('user-a',key)
            self.assertEqual((result['state'],result['processed'],result['downloaded']),('completed',2,2))

    def test_restart_requires_new_token_and_preserves_manifest(self):
        with self.manager() as m:
            key=self.schedule(m);m.close()
            next_manager=jobs.Manager(m.root.parent,m.storage,provider_factory=FakeProvider,start_worker=False,publisher=lambda src,dst:os.link(src,dst))
            try:
                result=next_manager.view('user-a',key)
                self.assertEqual((result['state'],result['errorCode']),('paused_auth','AUTH_REQUIRED'))
                self.assertFalse(next_manager.credentials)
                self.schedule(next_manager,key=key);next_manager.run_job(key)
                self.assertEqual(next_manager.view('user-a',key)['processed'],2)
            finally:next_manager.close()

    def test_cancel_inflight_prevents_publication_then_resumes(self):
        with self.manager() as m:
            key=self.schedule(m);FakeProvider.cancel_on_download=True;m.run_job(key)
            self.assertEqual(m.view('user-a',key)['state'],'cancelled')
            self.assertFalse(list(m.library.rglob('*.mp3')))
            FakeProvider.cancel_on_download=False;self.schedule(m,key=key);m.run_job(key)
            self.assertEqual(m.view('user-a',key)['processed'],2)

    def test_failed_prefix_does_not_block_later_ids_and_resume_counts_once(self):
        with self.manager() as m:
            key=self.schedule(m);FakeProvider.failure='NETWORK_ERROR';m.run_job(key)
            result=m.view('user-a',key)
            self.assertEqual((result['state'],result['failed'],result['downloaded'],result['processed']),('finished_with_errors',1,1,2))
            FakeProvider.failure=None;self.schedule(m,key=key);m.run_job(key);result=m.view('user-a',key)
            self.assertEqual((result['failed'],result['downloaded'],result['processed']),(0,2,2))
            self.assertEqual(FakeProvider.calls.count('2'),1)

    def test_atomic_publication_crash_intent_recovers_without_second_download(self):
        with self.manager() as m:
            original=m.publisher
            def crash(src,dst):original(src,dst);raise RuntimeError('private raw exception must never escape')
            m.publisher=crash;key=self.schedule(m);m.run_job(key)
            self.assertEqual(m.view('user-a',key)['state'],'failed')
            m.publisher=original;self.schedule(m,key=key);m.run_job(key);result=m.view('user-a',key)
            self.assertEqual((result['state'],result['skipped'],result['downloaded'],result['processed']),('completed',1,1,2))
            self.assertEqual(FakeProvider.calls.count('1'),1)

    def test_other_user_cannot_read_cancel_resume_and_one_job_per_user(self):
        with self.manager() as m:
            key=self.schedule(m)
            for operation in [lambda:m.view('other',key),lambda:m.cancel('other',key),lambda:m.schedule({'id':'other','token':'n'},TOKEN,key)]:
                with self.assertRaises(jobs.ApiError) as failure:operation()
                self.assertEqual(failure.exception.status,404)
            with self.assertRaises(jobs.ApiError) as failure:self.schedule(m)
            self.assertEqual(failure.exception.code,'JOB_EXISTS')
            m.cancel('user-a',key);self.assertEqual(m.view('user-a',key)['state'],'cancelled')
            new=self.schedule(m)
            with self.assertRaises(jobs.ApiError) as failure:self.schedule(m,key=key)
            self.assertEqual(failure.exception.code,'JOB_EXISTS')

    def test_cache_detects_missing_or_corrupt_file_and_never_overwrites_stranger(self):
        with self.manager() as m:
            key=self.schedule(m);m.run_job(key)
            destination=m.library/'yandex/1.mp3';destination.write_bytes(b'foreign data')
            self.assertIsNone(m.cached('1'))
            other=self.schedule(m,{'id':'user-b','token':'new-native'})
            m.run_job(other)
            self.assertEqual(destination.read_bytes(),b'foreign data')

    def test_classification_excludes_spoken_noise_and_unknown(self):
        artist=types.SimpleNamespace(name='Name')
        album=types.SimpleNamespace(type='album',meta_type='music',artists=[artist],genre='Rock',title='Album')
        track=types.SimpleNamespace(id=123,type='music',albums=[album],artists=[artist],title='Song',duration_ms=1000,available=True)
        self.assertEqual(source.describe(track)['kind'],'music')
        for kind in ('audiobook','podcast','podcast-episode','noise',None):
            track.type=kind;self.assertEqual(source.describe(track)['kind'],'excluded')
        track.type='music';album.meta_type='podcast';self.assertEqual(source.describe(track)['kind'],'excluded')

    def test_source_url_and_identifier_allowlists(self):
        for url in ('http://api.music.yandex.net/a','https://localhost/a','https://api.music.yandex.net.evil/a','https://user:secret@api.music.yandex.net/a'):
            with self.assertRaises(ImportFailure):source.remote_url(url)
        for value in ('../1','1/file','file.mp3','token'):
            with self.assertRaises(ImportFailure):source.source_id(value)

    def test_omitted_metadata_is_not_claimed_non_music(self):
        with self.manager() as m,patch.object(FakeProvider,'metadata',return_value={}):
            key=self.schedule(m);m.run_job(key);value=m.view('user-a',key)
            self.assertEqual((value['state'],value['excluded'],value['processed']),('failed',0,0))

    def test_explicit_resume_rechecks_unavailable_without_redownloading_completed(self):
        with self.manager() as m:
            FakeProvider.failure='SOURCE_UNAVAILABLE';key=self.schedule(m);m.run_job(key)
            self.assertEqual(m.view('user-a',key)['unavailable'],1)
            FakeProvider.failure=None;self.schedule(m,key=key);m.run_job(key);value=m.view('user-a',key)
            self.assertEqual((value['state'],value['unavailable'],value['downloaded'],value['processed']),('completed',0,2,2))
            self.assertEqual(FakeProvider.calls.count('2'),1)

    def test_account_revoked_during_download_cannot_publish_or_resume(self):
        with self.manager() as m:
            key=self.schedule(m)
            original=FakeProvider.download
            def revoked(provider,source_id,path):
                result=original(provider,source_id,path)
                m.account_guard=lambda user:False
                return result
            with patch.object(FakeProvider,'download',revoked):m.run_job(key)
            self.assertEqual(m.view('user-a',key)['state'],'paused_auth')
            self.assertEqual(list((m.library/'yandex').glob('*')),[])
            with self.assertRaises(ImportFailure):self.schedule(m,key=key)
            m.cancel_uid('user-a')
            self.assertEqual(m.view('user-a',key)['state'],'cancelled')
            self.assertNotIn(key,m.credentials)

if __name__=='__main__':unittest.main()
