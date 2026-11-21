import contextlib
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import types
import unittest
from email.message import Message
from unittest.mock import Mock,patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'api'))
import content_ratings as ratings
import media_api as media
import profile_api as profile
import audio
import radio
from radio_catalog import Catalog,dto
import test_radio as fixtures
import yandex_source
import admin_api

spec=importlib.util.spec_from_file_location('backfill',Path(__file__).resolve().parents[1]/'backfill-content-ratings.py')
backfill=importlib.util.module_from_spec(spec);spec.loader.exec_module(backfill)


class RatingsTests(unittest.TestCase):
    def fixture(self,root):
        catalog=fixtures.CatalogTests().fixture(root)
        with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:
            db.execute("ALTER TABLE media_file ADD COLUMN explicit_status TEXT NOT NULL DEFAULT ''")
        catalog=Catalog(catalog.path,catalog.roots)
        store=ratings.Store(root/'state',catalog);catalog.ratings=store
        return catalog,store

    def test_provider_mapping_preserves_unknown_and_explicit_wins(self):
        for value,expected in [({},''),({'content_warning':None},''),({'explicit':False},'clean'),
          ({'explicit':True},'explicit'),({'content_warning':'explicit'},'explicit'),
          ({'content_warning':'explicit','explicit':False},'explicit'),
          ({'content_warning':'other','explicit':False},''),({'explicit':0},''),({'explicit':'false'},'')]:
            self.assertEqual(ratings.provider_status(types.SimpleNamespace(**value)),expected)

    def test_packaged_docker_copy_set_imports_runtime_entry_modules(self):
        api=Path(__file__).resolve().parents[1]/'api'
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            for line in (api/'Dockerfile').read_text().splitlines():
                if line.startswith('COPY ') and line.endswith(' ./'):
                    for name in line.split()[1:-1]:(root/name).write_bytes((api/name).read_bytes())
            script="import sys;sys.path.insert(0,sys.argv[1]);import radio,media_api,yandex_source;assert radio.RatingStore is media_api.RatingStore"
            result=subprocess.run([sys.executable,'-I','-c',script,str(root)],capture_output=True,text=True)
            self.assertEqual(result.returncode,0,result.stderr)

    def test_durable_file_bound_overlay_native_explicit_and_visibility(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root)
            a=catalog.entries('u',['a'])['a'];store.put_verified(a,'123','clean',store.signature(a))
            other=catalog.entries('v',['b'])['b'];store.put_verified(other,'456','explicit',store.signature(other))
            store.put_verified(other,'456','',store.signature(other))
            self.assertEqual(store.query('v',{'ids':['b']}),{'ratings':{'b':'explicit'},'overrides':{}})
            self.assertEqual(store.query('u',{'ids':['a','b','missing']}),{'ratings':{'a':'clean'},'overrides':{}})
            store=ratings.Store(root/'state',catalog);catalog.ratings=store
            self.assertEqual(dto(catalog.songs('u')['a'])['explicitStatus'],'clean')
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:db.execute("UPDATE media_file SET explicit_status='e' WHERE id='a'")
            self.assertEqual(store.query('u',{'ids':['a']})['ratings']['a'],'explicit')
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:db.execute("UPDATE media_file SET explicit_status='' WHERE id='a'")
            (root/'music/a.mp3').write_bytes(b'replaced audio')
            self.assertEqual(store.query('u',{'ids':['a']}),{'ratings':{},'overrides':{}})
            with self.assertRaises(ValueError):store.put_verified(a,'123','clean',('1','a.mp3',5,0,0,0))

    def test_native_clean_cannot_override_explicit_overlay_and_missing_file_is_not_clean(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root)
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:db.execute("UPDATE media_file SET explicit_status='c' WHERE id='a'")
            a=catalog.entries('u',['a'])['a'];store.put_verified(a,'123','explicit',store.signature(a))
            self.assertEqual(store.query('u',{'ids':['a']})['ratings']['a'],'explicit')
            (root/'music/a.mp3').unlink()
            self.assertEqual(store.query('u',{'ids':['a']}),{'ratings':{},'overrides':{}})

    def test_request_limits(self):
        for body in ({},{'ids':'a'},{'ids':[None]},{'ids':['../a']},{'ids':['a'*81]},{'ids':['a']*501},{'ids':['a'],'extra':True}):
            with self.assertRaises(profile.ProfileError):ratings.request_ids(body)
        self.assertEqual(ratings.request_ids({'ids':[]}),[])

    def test_manual_clean_wins_native_explicit_and_provider_cannot_replace_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root)
            accounts=admin_api.Accounts(root/'state',catalog.path,root/'music')
            identity={'id':'u','username':'Admin','token':'memory-only','isAdmin':False}
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:db.execute("UPDATE media_file SET explicit_status='e' WHERE id='a'")
            song=catalog.entries('u',['a'])['a'];signature=store.signature(song)
            store.put_verified(song,'123','explicit',signature)
            with patch.object(admin_api,'SERVICE',accounts),patch.object(admin_api,'native',return_value={'id':'u','userName':'Admin','isAdmin':True}) as native:
                result=store.override(identity,{'id':'a','status':'clean'})
                self.assertEqual(result,{'ratings':{'a':'clean'},'overrides':{'a':'clean'}})
                self.assertGreaterEqual(native.call_count,2)
                store.put_verified(song,'123','explicit',signature)
                self.assertEqual(store.query('u',{'ids':['a']}),result)
                item=dto(catalog.songs('u')['a'])
                self.assertEqual((item['explicitStatus'],item['contentRatingOverride']),('clean',True))
                self.assertEqual(store.override(identity,{'id':'a','status':'explicit'})['overrides'],{'a':'explicit'})
                self.assertEqual(store.override(identity,{'id':'a','status':'source'}),{'ratings':{'a':'explicit'},'overrides':{}})
            self.assertFalse(dto(catalog.songs('u')['a'])['contentRatingOverride'])

    def test_manual_role_revocation_visibility_and_physical_invalidation(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root)
            accounts=admin_api.Accounts(root/'state',catalog.path,root/'music')
            identity={'id':'u','username':'Admin','token':'memory-only','isAdmin':True}
            with patch.object(admin_api,'SERVICE',accounts),patch.object(admin_api,'native',return_value={'id':'u','userName':'Admin','isAdmin':False}):
                with self.assertRaises(profile.ProfileError) as error:store.override(identity,{'id':'a','status':'clean'})
                self.assertEqual(error.exception.status,403)
            with patch.object(admin_api,'SERVICE',accounts),patch.object(admin_api,'native',return_value={'id':'u','userName':'Admin','isAdmin':True}):
                for key in ('b','missing'):
                    with self.assertRaises(profile.ProfileError) as error:store.override(identity,{'id':key,'status':'clean'})
                    self.assertEqual(error.exception.status,404)
                store.override(identity,{'id':'a','status':'explicit'})
                with store.db() as db:self.assertEqual(db.execute('SELECT actor FROM overrides').fetchone()[0],'u')
                self.assertNotIn('u',json.dumps(store.query('u',{'ids':['a']})))
                (root/'music/a.mp3').write_bytes(b'replacement performance')
                self.assertEqual(store.query('u',{'ids':['a']}),{'ratings':{},'overrides':{}})
                store.override(identity,{'id':'a','status':'source'})
                with store.db() as db:self.assertEqual(db.execute('SELECT count(*) FROM overrides').fetchone()[0],0)
                with accounts.db() as db:db.execute("INSERT INTO tombstones VALUES('admin','u','now',NULL)")
                with self.assertRaises(admin_api.AdminError) as error:store.override(identity,{'id':'a','status':'clean'})
                self.assertEqual(error.exception.status,401)

    def test_override_route_checks_actual_native_role_and_authentication(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root);accounts=admin_api.Accounts(root/'state',catalog.path,root/'music')
            manager=types.SimpleNamespace(catalog=catalog)
            data=json.dumps({'id':'a','status':'clean'}).encode();headers=Message();headers['Content-Length']=str(len(data))
            def call(identity,native_user):
                handler=types.SimpleNamespace(path=profile.PREFIX+'/content-ratings/override',command='POST',headers=headers,rfile=io.BytesIO(data));output=[]
                auth=patch.object(profile,'authenticate',return_value=identity) if identity else patch.object(profile,'authenticate',side_effect=profile.ProfileError(401))
                with auth,patch.object(media,'UPLOAD',object()),patch.object(media,'LYRICS',manager),patch.object(media,'guard',return_value=None),patch.object(admin_api,'SERVICE',accounts),patch.object(admin_api,'native',return_value=native_user),patch.object(profile,'send',side_effect=lambda h,s,v:output.append((s,v))):
                    media.dispatch(handler,audio.body_chunks)
                return output[0]
            user={'id':'u','username':'Admin','token':'memory-only','isAdmin':True}
            self.assertEqual(call(None,{})[0],401)
            self.assertEqual(call(user,{'id':'u','userName':'Admin','isAdmin':False})[0],403)
            self.assertEqual(call(user,{'id':'u','userName':'Admin','isAdmin':True}),(200,{'ratings':{'a':'clean'},'overrides':{'a':'clean'}}))
            self.assertTrue(media.owns(profile.PREFIX+'/content-ratings/override'))

    def test_endpoint_auth_guard_limits_no_visibility_leak(self):
        with tempfile.TemporaryDirectory() as tmp:
            catalog,store=self.fixture(Path(tmp));manager=types.SimpleNamespace(catalog=catalog)
            def call(body,identity={'id':'u'},method='POST'):
                encoded=json.dumps(body).encode();headers=Message();headers['Content-Length']=str(len(encoded))
                handler=types.SimpleNamespace(path=profile.PREFIX+'/content-ratings',command=method,headers=headers,rfile=io.BytesIO(encoded));output=[]
                auth=patch.object(profile,'authenticate',return_value=identity) if identity else patch.object(profile,'authenticate',side_effect=profile.ProfileError(401))
                with auth,patch.object(media,'UPLOAD',object()),patch.object(media,'LYRICS',manager),patch.object(media,'guard',return_value=None),patch.object(profile,'send',side_effect=lambda h,s,v:output.append((s,v))):
                    media.dispatch(handler,audio.body_chunks)
                return output[0]
            self.assertEqual(call({'ids':['a']},None)[0],401)
            self.assertEqual(call({'ids':['b','missing']}),(200,{'ratings':{},'overrides':{}}))
            self.assertEqual(call({'ids':['a']*501})[0],400)
            self.assertEqual(call({'ids':['a']},method='GET')[0],405)

    def test_child_radio_blocks_only_explicit_before_taste_and_all_fallbacks(self):
        rows={key:fixtures.song(key,starred=True) for key in ('clean','explicit','unknown')}
        rows['clean']['explicitStatus']='clean';rows['explicit']['explicitStatus']='explicit'
        with tempfile.TemporaryDirectory() as tmp:
            engine=fixtures.RadioTests().engine(tmp,{'u':rows},{key:fixtures.acoustic() for key in rows})
            for mode in ('FAVORITES','DISCOVERY','BALANCED'):
                with engine.db() as db:db.execute('DELETE FROM served')
                result=engine._mix('u',radio.request_mix({'childMode':True,'mode':mode}))
                self.assertEqual({s['id'] for s in result['songs']},{'clean','unknown'})
                self.assertEqual(result['analysis']['total'],2)
                self.assertEqual(set(engine.index.priority),{'clean','unknown'})
            rows['clean']['explicitStatus']=''
            with engine.db() as db:db.execute('DELETE FROM served')
            self.assertEqual({s['id'] for s in engine._mix('u',radio.request_mix({'childMode':True}))['songs']},{'clean','unknown'})
        for invalid in (0,1,None,'true'):
            with self.assertRaises(radio.Rejected):radio.request_mix({'childMode':invalid})

    def test_backfill_exact_hash_resume_and_changed_file_refusal(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);catalog,store=self.fixture(root);library=root/'music';(library/'tracks').mkdir()
            path=library/'tracks/123.mp3';path.write_bytes(b'proven original')
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:
                db.execute("UPDATE media_file SET path='tracks/123.mp3' WHERE id='a'")
            downloads=root/'downloads.sqlite'
            with contextlib.closing(sqlite3.connect(downloads)) as db,db:
                db.execute('CREATE TABLE downloads(id,path,bytes,sha,origin)')
                db.execute('INSERT INTO downloads VALUES(?,?,?,?,?)',('123','tracks/123.mp3',path.stat().st_size,hashlib.sha256(path.read_bytes()).hexdigest(),'legacy_verified'))
            token=root/'worker.token';token.write_text('private-test-token');token.chmod(0o600)
            args=types.SimpleNamespace(nav_db=catalog.path,library_root=library,downloads_db=downloads,state_root=root/'state',token_file=token,apply=True,max_batches=1,interval=1)
            provider=Mock();provider.metadata.return_value={'123':{'explicitStatus':'clean'}}
            fake_lock=types.SimpleNamespace(LOCK_EX=1,LOCK_NB=2,flock=lambda *args:None)
            # Windows does not enforce POSIX permission bits; isolate only OS facilities.
            original_lstat=Path.lstat
            def private_lstat(p):
                info=original_lstat(p)
                if p==token:return types.SimpleNamespace(st_mode=stat_mode,st_uid=os.getuid())
                return info
            import stat
            stat_mode=stat.S_IFREG|0o600
            with patch.dict(sys.modules,{'fcntl':fake_lock}),patch.object(backfill.time,'sleep'),patch.object(Path,'lstat',private_lstat),patch.object(os,'getuid',return_value=123,create=True):
                first=backfill.run(args,lambda *args:provider)
                second=backfill.run(args,lambda *args:provider)
                self.assertEqual((first['clean'],second['alreadyCurrent']),(1,1))
                provider.metadata.assert_called_once_with(['123'])
                self.assertEqual(path.read_bytes(),b'proven original')
                path.write_bytes(b'wrong recording')
                third=backfill.run(args,lambda *args:provider)
                self.assertEqual(third['hashRejected'],1)
                self.assertEqual(store.query('u',{'ids':['a']}),{'ratings':{},'overrides':{}})


if __name__=='__main__':unittest.main()
