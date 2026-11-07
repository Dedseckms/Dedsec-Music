# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import stat
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT));sys.path.insert(0,str(ROOT/'api'))
import kit
import yandex_import as jobs
import yandex_source as source
from import_errors import ImportFailure

spec=importlib.util.spec_from_file_location('seed_index',ROOT/'seed-yandex-index.py');seed=importlib.util.module_from_spec(spec);spec.loader.exec_module(seed)


class RecoveryTests(unittest.TestCase):
    def test_seed_requires_approved_source_exact_path_size_and_hash_not_similar_name(self):
        with tempfile.TemporaryDirectory() as temp,contextlib.closing(sqlite3.connect(':memory:')) as db:
            root=Path(temp);(root/'tracks').mkdir();(root/'uploads').mkdir()
            data=b'known-audio';checksum=hashlib.sha256(data).hexdigest()
            db.execute('CREATE TABLE downloads(id TEXT,path TEXT,bytes INTEGER,sha256 TEXT,seconds REAL)')
            for key,path in [('1','tracks/1.mp3'),('2','uploads/2.mp3'),('3','tracks/3.mp3'),('4','tracks/4.mp3')]:
                (root/path).write_bytes(data if key!='3' else b'corrupted')
                db.execute('INSERT INTO downloads VALUES(?,?,?,?,?)',(key,path,len(data),checksum,1))
            rows,reasons=seed.candidates(db,root,{'sections':[{'track_ids':['1','2','3']}]})
            self.assertEqual([row[0] for row in rows],['1']);self.assertEqual(rows[0][-1],'legacy_verified')
            self.assertEqual(sum(reasons.values()),3);self.assertEqual((root/'tracks/1.mp3').read_bytes(),data)

    def test_seed_cache_skips_verified_legacy_file_without_audio_copy(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);manager=jobs.Manager(root/'state',root/'storage',start_worker=False)
            try:
                folder=manager.library/'tracks';folder.mkdir();path=folder/'42.mp3';path.write_bytes(b'known-audio')
                with manager.db() as db:db.execute('INSERT INTO downloads VALUES(?,?,?,?,?,?)',('42','tracks/42.mp3',11,seed.sha(path),1,'legacy_verified'))
                self.assertIsNotNone(manager.cached('42'));self.assertEqual(len(list(manager.library.rglob('*.mp3'))),1)
            finally:manager.close()

    def test_orphan_cleanup_preserves_published_hardlink_unknown_active_and_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);pending=root/('import-'+'a'*32+'.pending');pending.write_bytes(b'audio')
            published=root/'published.mp3';os.link(pending,published)
            unknown=root/'personal.pending';unknown.write_bytes(b'keep')
            active=root/'audio-abcdefgh.pending';active.write_bytes(b'active')
            link=root/('import-'+'b'*32+'.pending')
            symlink=False
            try:link.symlink_to(published);symlink=True
            except OSError:pass
            def metadata(path):
                if path==link:return types.SimpleNamespace(st_mode=stat.S_IFLNK|0o600,st_uid=1000,st_mtime=1)
                return types.SimpleNamespace(st_mode=stat.S_IFREG|0o600,st_uid=1000,st_mtime=10 if path==active else 1)
            jobs.cleanup_staging(root,5,1000,metadata)
            self.assertFalse(pending.exists());self.assertEqual(published.read_bytes(),b'audio')
            self.assertTrue(unknown.exists());self.assertTrue(active.exists())
            if symlink:self.assertTrue(link.is_symlink())

    def test_import_journal_online_backup_includes_committed_wal(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source_path=root/'jobs.sqlite';target=root/'copy.sqlite'
            with contextlib.closing(sqlite3.connect(source_path)) as db:
                db.execute('PRAGMA journal_mode=WAL');db.execute('CREATE TABLE example(value INTEGER)');db.execute('INSERT INTO example VALUES(123)');db.commit()
                kit.online_backup(source_path,target)
                with contextlib.closing(sqlite3.connect(target)) as copy:self.assertEqual(copy.execute('SELECT value FROM example').fetchone()[0],123)

    def test_compose_env_uses_validated_uid_even_with_root_shell_override(self):
        values={'STACK_NAME':'test-stack','LOCAL_UID':'1000','LOCAL_GID':'1000','MUSIC_DOMAIN':'music.test.org','ACME_EMAIL':'a@test.org'}
        with patch.object(kit,'config',return_value=values),patch.dict(os.environ,{'LOCAL_UID':'0'}),patch.object(kit.subprocess,'run') as run:
            kit.compose('config','--quiet')
            self.assertEqual(run.call_args.kwargs['env']['LOCAL_UID'],'1000')

    def test_actual_pinned_sdk_auth_reaches_only_api_session(self):
        from yandex_music import Client
        calls=[]
        class Response:
            status_code=200
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def iter_content(self,*args):yield b'{"result":{}}'
        class Session:
            trust_env=False
            def request(self,method,url,**kwargs):calls.append((url,kwargs));return Response()
            def close(self):pass
        def initialize(client):
            client.request.get('https://api.music.yandex.net/fixture')
            client.request.post('https://api.music.yandex.net/fixture',data={})
            client.me=types.SimpleNamespace(account=types.SimpleNamespace(uid=123));return client
        with patch('requests.Session',side_effect=Session),patch.object(Client,'init',initialize):
            provider=source.Provider('secret-fixture-token',threading.Event())
            try:
                self.assertEqual(len(calls),2)
                self.assertTrue(all(kwargs['headers']['Authorization']=='OAuth secret-fixture-token' for _,kwargs in calls))
                self.assertFalse(hasattr(provider.download_session,'headers') and provider.download_session.headers)
            finally:provider.close()


if __name__=='__main__':unittest.main()
