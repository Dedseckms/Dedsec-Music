# SPDX-License-Identifier: GPL-3.0-or-later
import json
import base64
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'api'))
import radio_neural as neural


class NeuralTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name).resolve()
        self.audio=self.root/'track';self.audio.write_bytes(b'audio')
        self.song={'id':'visible','duration':180}
        parent=self
        class Catalog:
            def songs(self):return {'visible':parent.song}
            def physical(self,song):
                info=parent.audio.stat();return parent.audio,info.st_size,info.st_mtime_ns
        self.cache=neural.Cache(self.root,Catalog());self.cache.token_path.write_text('a'*64);self.cache.token_path.chmod(0o600)

    def tearDown(self):self.temp.cleanup()

    def vector(self,index):
        value=[0.0]*521;value[index]=1.0;return value

    def result(self,job,scores):return {'lease':job['lease'],'model':neural.MODEL,'scores':scores}

    def test_one_lease_scoped_cache_invalidation_and_cancel(self):
        job=self.cache.next(lambda _:bytes(15600*2));self.assertIsNone(self.cache.next())
        self.assertNotIn('visible',json.dumps(job));self.assertNotIn(str(self.audio),json.dumps(job))
        self.cache.complete(self.result(job,self.vector(0)))
        self.assertEqual(list(self.cache.available({'visible':self.song})),['visible'])
        self.assertEqual(self.cache.available({}),{})
        self.audio.write_bytes(b'replaced');self.assertEqual(self.cache.available({'visible':self.song}),{})
        with self.cache.db() as db:db.execute('UPDATE neural SET updated=0')
        self.cache.last_lease=0;job=self.cache.next(lambda _:bytes(15600*2))
        (self.root/'neural.pause').touch()
        with self.assertRaises(ValueError):self.cache.complete(self.result(job,self.vector(0)))
        self.assertEqual(self.cache.available({'visible':self.song}),{})

    def test_expired_wrong_model_nan_and_wrong_lease_rejected(self):
        job=self.cache.next(lambda _:bytes(15600*2));result=self.result(job,self.vector(0))
        for change in ({'model':'other'},{'lease':'wrong'},{'scores':[float('nan')]*521},{'scores':[0]*521},{'scores':[1]*520}):
            with self.assertRaises(ValueError):self.cache.complete({**result,**change})
        self.cache.lease['expires']=time.time()-1
        with self.assertRaises(ValueError):self.cache.complete(result)

    def test_failure_retry_does_not_publish_or_block_playback(self):
        job=self.cache.next(lambda _:bytes(15600*2));self.cache.complete(self.result(job,None))
        self.cache.last_lease=0;self.assertIsNone(self.cache.next(lambda _:bytes(15600*2)))
        self.assertEqual(self.cache.available({'visible':self.song}),{})

    def test_neural_affinity_liked_listened_vs_disliked_not_random(self):
        a=neural.unit(self.vector(0));b=neural.unit(self.vector(1))
        values={'favorite':a,'similar':a,'disliked':b,'unlike':b}
        scores=neural.affinity(values,{'favorite':8,'disliked':-3})
        self.assertGreater(scores['similar'],scores['unlike']);self.assertEqual(scores['similar'],3)
        self.assertEqual(neural.affinity(values,{}),dict.fromkeys(values,0))

    def test_extraction_lock_does_not_hold_cache_lock(self):
        def extract(_):
            self.assertTrue(self.cache.lock.acquire(blocking=False));self.cache.lock.release()
            self.assertEqual(self.cache.available({'visible':self.song}),{})
            return bytes(15600*2)
        self.cache.next(extract)

    def test_tiny_scores_survive_quantization(self):
        job=self.cache.next(lambda _:bytes(15600*2))
        self.cache.complete(self.result(job,[.0001]*521))
        self.assertIn('visible',self.cache.available({'visible':self.song}))

    def test_bad_source_gets_backoff_instead_of_blocking_the_queue(self):
        def failure(_):raise ValueError('Bad source')
        self.assertIsNone(self.cache.next(failure))
        self.cache.last_lease=0
        self.assertIsNone(self.cache.next(lambda _:self.fail('Failed source retried without backoff')))
        self.assertEqual(self.cache.available({'visible':self.song}),{})

    def test_available_only_checks_a_bounded_visible_subset(self):
        physical=self.cache.catalog.physical
        calls=[]
        self.cache.catalog.physical=lambda song:(calls.append(song['id']) or physical(song))
        with self.cache.db() as db:
            for i in range(400):
                song={'id':str(i),'duration':180}
                db.execute('INSERT INTO neural VALUES(?,?,?,?,?)',(str(i),self.cache.signature(song),neural.MODEL,base64.b64encode(bytes([255]+[0]*520)).decode(),0))
        calls.clear()
        result=self.cache.available({str(i):{'id':str(i),'duration':180} for i in range(400)})
        self.assertEqual(len(calls),384);self.assertEqual(len(result),384)

    def test_actual_mix_prefers_neural_similarity_to_excluded_favorite(self):
        import radio
        import random
        from test_radio import FakeCatalog,FakeIndex,song
        rows={key:song(key,starred=key=='seed') for key in ('seed','similar','other')}
        engine=radio.Engine(FakeCatalog({'u':rows}),FakeIndex({}),self.root,rng=random.Random(4))
        vectors={'seed':neural.unit(self.vector(0)),'similar':neural.unit(self.vector(0)),'other':neural.unit(self.vector(1))}
        class Cached:
            def available(self,songs):return {key:value for key,value in vectors.items() if key in songs}
            def prioritize(self,keys):pass
        engine.neural=Cached();matches=0
        for _ in range(100):
            engine.limits.clear()
            with engine.db() as db:db.execute('DELETE FROM served')
            result=engine.mix('u',radio.request_mix({'size':1,'excludeIds':['seed']}))
            matches+=result['songs'][0]['id']=='similar'
        self.assertGreater(matches,80)


if __name__=='__main__':unittest.main()
