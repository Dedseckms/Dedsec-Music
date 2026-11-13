# SPDX-License-Identifier: GPL-3.0-or-later
import array
import contextlib
import copy
import json
import math
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'api'))
import radio
from radio_catalog import Catalog,dto,epoch
import radio_features as features


def song(key,artist='artist',genres=None,starred=False,played=0):
    return {'id':key,'title':'Track '+key,'artist':artist,'artist_id':artist,'album':'Album','album_id':'album',
        'genres':genres or ['pop'],'starred':starred,'play_count':played,'rating':0,'duration':180,'size':1000,
        'suffix':'mp3','library_id':1,'path':key+'.mp3','has_cover_art':True}


def acoustic(energy='high'):
    return {'rms':-8 if energy=='high' else -29,'centroid':2200 if energy=='high' else 350,
        'flux':2 if energy=='high' else .01,'zcr':.2 if energy=='high' else .02,
        'tempo':125 if energy=='high' else 75,'tempoConfidence':.8}


class FakeCatalog:
    def __init__(self,rows):self.rows=rows
    def songs(self,uid=None):return copy.deepcopy(self.rows.get(uid,{}) if uid else {k:v for rows in self.rows.values() for k,v in rows.items()})
    def song(self,uid,key):return copy.deepcopy(self.rows.get(uid,{}).get(key))


class FakeIndex:
    def __init__(self,values):self.values=values;self.priority=[]
    def available(self,songs):return {key:self.values[key] for key in songs if key in self.values}
    def prioritize(self,ids):self.priority=list(ids)


class RadioTests(unittest.TestCase):
    def engine(self,root,rows,values):return radio.Engine(FakeCatalog(rows),FakeIndex(values),root)
    def request(self,**kwargs):return radio.request_mix(dict(mood='ANY',mode='BALANCED',size=40,history=[],dislikedIds=[],excludeIds=[],**kwargs))

    def test_moods_are_hard_filters_never_fill_with_opposite_or_unknown_audio(self):
        rows={'quiet':song('quiet',genres=['ambient']),'dance':song('dance',genres=['dance']),'unknown':song('unknown')}
        with tempfile.TemporaryDirectory() as root:
            engine=self.engine(root,{'u':rows},{'quiet':acoustic('low'),'dance':acoustic()})
            for mood,expected in [('CHILL',['quiet']),('ENERGY',['dance']),('WORKOUT',['dance']),('PARTY',['dance']),('FOCUS',['quiet']),('HAPPY',['dance']),('SAD',[])]:
                with engine.db() as db:db.execute('DELETE FROM served')
                result=engine.mix('u',radio.request_mix({'mood':mood,'mode':'BALANCED','size':40}))
                self.assertEqual([item['id'] for item in result['songs']],expected,mood)
                if not expected:self.assertEqual(result['reason'],'ANALYZING')

    def test_favorites_are_hard_own_starred_and_other_account_data_invisible(self):
        rows={'u':{'own':song('own',starred=True),'not':song('not')},'v':{'secret':song('secret',starred=True)}}
        with tempfile.TemporaryDirectory() as root:
            engine=self.engine(root,rows,{key:acoustic() for key in ['own','not','secret']})
            result=engine.mix('u',radio.request_mix({'mode':'FAVORITES','history':[{'id':'secret','count':99,'playedAt':0}]}))
            self.assertEqual([s['id'] for s in result['songs']],['own']);self.assertEqual(result['analysis'],{'indexed':2,'total':2})

    def test_discovery_penalizes_known_and_taste_beats_small_random_tie_break(self):
        rows={'liked':song('liked',genres=['rock'],starred=True,played=5),'new':song('new',genres=['rock']),'off':song('off',genres=['classical'])}
        with tempfile.TemporaryDirectory() as root:
            engine=self.engine(root,{'u':rows},{})
            result=engine.mix('u',radio.request_mix({'mode':'DISCOVERY','size':3}))
            ids=[row['id'] for row in result['songs']];self.assertEqual(ids[0],'new');self.assertGreater(ids.index('liked'),ids.index('off'))

    def test_whole_catalog_beyond300_is_ranked_and_adjacent_artists_diverse(self):
        rows={str(i):song(str(i),artist='same',genres=['classical']) for i in range(400)}
        rows['last']=song('last',artist='preferred',genres=['rock'],starred=True)
        rows['similar']=song('similar',artist='other',genres=['rock'])
        with tempfile.TemporaryDirectory() as root:
            engine=self.engine(root,{'u':rows},{})
            result=engine.mix('u',radio.request_mix({'size':3}))
            self.assertEqual(result['songs'][0]['id'],'last');self.assertEqual(result['songs'][1]['id'],'similar')
            self.assertEqual(result['analysis']['total'],402)

    def test_feedback_dislike_is_private_visible_only_and_retry_does_not_double_count(self):
        rows={'u':{'a':song('a')},'v':{'b':song('b')}}
        with tempfile.TemporaryDirectory() as root:
            engine=self.engine(root,rows,{})
            event={'id':'a','kind':'skipped','positionMs':10000,'durationMs':1}
            with patch.object(engine.catalog,'songs',side_effect=AssertionError('Feedback must not scan catalogue')):
                engine.event('u',event);engine.event('u',event)
            with engine.db() as db:self.assertEqual(db.execute('SELECT skips FROM feedback').fetchone()[0],1)
            with self.assertRaises(radio.Rejected) as e:engine.event('u',dict(event,id='b'))
            self.assertEqual(e.exception.status,404)
            engine.event('u',dict(event,kind='disliked'));self.assertFalse(engine.mix('u',radio.request_mix({}))['songs'])
            self.assertEqual([s['id'] for s in engine.mix('v',radio.request_mix({}))['songs']],['b'])

    def test_input_bounds_strict_enums_bool_ids_and_response_starred_shape(self):
        for value in [{'size':True},{'mood':'fast'},{'mode':'ALL'},{'history':[{'id':'a','count':1,'playedAt':2**63}]},
                      {'dislikedIds':['x']*1001},{'excludeIds':['../file']}]:
            with self.assertRaises(radio.Rejected):radio.request_mix(value)
        self.assertNotIn('starred',dto(song('plain')));self.assertTrue(dto(song('fav',starred=True))['starred'])


class CatalogTests(unittest.TestCase):
    def fixture(self,root):
        database=root/'nav.sqlite';library=root/'music';library.mkdir()
        with contextlib.closing(sqlite3.connect(database)) as db,db:
            db.executescript('''CREATE TABLE media_file(id TEXT,path TEXT,title TEXT,artist TEXT,artist_id TEXT,album TEXT,album_id TEXT,has_cover_art INTEGER,
              duration REAL,size INTEGER,suffix TEXT,missing INTEGER,library_id INTEGER,genre TEXT);
              CREATE TABLE user_library(user_id TEXT,library_id INTEGER);
              CREATE TABLE annotation(user_id TEXT,item_id TEXT,item_type TEXT,starred INTEGER,play_count INTEGER,rating INTEGER,play_date TEXT,starred_at TEXT);
              CREATE TABLE tag(id TEXT,tag_name TEXT,tag_value TEXT);CREATE TABLE media_file_tags(media_file_id TEXT,tag_id TEXT);''')
            for key,lib in [('a',1),('b',2)]:
                db.execute('INSERT INTO media_file VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)',(key,key+'.mp3',key,'artist','ar','album','al',1,180,5,'mp3',0,lib,'DEPRECATED_WRONG'))
                (library/(key+'.mp3')).write_bytes(b'audio')
            db.executemany('INSERT INTO user_library VALUES(?,?)',[('u',1),('v',2)])
            db.execute("INSERT INTO annotation VALUES('v','a','media_file',1,100,5,NULL,NULL)")
            db.execute("INSERT INTO tag VALUES('genre-id','genre','Correct genre')");db.execute("INSERT INTO media_file_tags VALUES('a','genre-id')")
        return Catalog(database,{'1':library,'2':library})

    def test_actual_tag_join_visibility_annotations_and_query_only(self):
        with tempfile.TemporaryDirectory() as temp:
            catalog=self.fixture(Path(temp));rows=catalog.songs('u')
            self.assertEqual(set(rows),{'a'});self.assertEqual(rows['a']['genres'],['Correct genre']);self.assertEqual(rows['a']['starred'],0)
            self.assertEqual(catalog.song('u','a'),{'id':'a','duration':180});self.assertIsNone(catalog.song('u','b'))
            with catalog.db() as db:
                with self.assertRaises(sqlite3.OperationalError):db.execute('DELETE FROM media_file')

    def test_history_offset_is_preserved(self):
        self.assertEqual(epoch('2026-11-12T15:00:00+03:00'),epoch('2026-11-12T12:00:00Z'))

    def test_large_user_tags_are_truncated_inside_catalog_selection(self):
        with tempfile.TemporaryDirectory() as temp:
            catalog=self.fixture(Path(temp))
            with contextlib.closing(sqlite3.connect(catalog.path)) as db,db:
                db.execute('UPDATE media_file SET title=?,artist=?,album=?',('x'*100000,)*3)
                db.execute('UPDATE tag SET tag_value=?',('g'*100000,))
            value=catalog.songs('u')['a']
            self.assertEqual([len(value[k]) for k in ('title','artist','album')],[320]*3)
            self.assertEqual(len(value['genres'][0]),120)

    def test_strict_physical_paths_no_traversal_or_symlink(self):
        with tempfile.TemporaryDirectory() as temp:
            catalog=self.fixture(Path(temp));s=catalog.songs('u')['a'];self.assertIsNotNone(catalog.physical(s))
            for value in ['../outside.mp3','/etc/passwd','a\\file.mp3']:
                self.assertIsNone(catalog.physical(dict(s,path=value)))


class FeatureTests(unittest.TestCase):
    def test_real_envelope_autocorrelation_tempo_and_confidence(self):
        samples=array.array('h',(int(math.sin(i*2*math.pi*440/features.SAMPLE_RATE)*12000*(1 if i%(features.SAMPLE_RATE//2)<900 else .03)) for i in range(features.SAMPLE_RATE*12)))
        if sys.byteorder!='little':samples.byteswap()
        value=features.pcm_features(samples.tobytes())
        self.assertAlmostEqual(value['tempo'],120,delta=4);self.assertGreater(value['tempoConfidence'],.3)

    def test_index_cache_invalidates_mtime_size_and_busy_upload_slot_prevents_decode(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);catalog=CatalogTests().fixture(root);slot=threading.BoundedSemaphore(1);index=features.Index(root/'radio',catalog,slot,start=False)
            calls=[]
            def extract(*args):calls.append(args);return acoustic()
            rows=catalog.songs('u');self.assertTrue(index.scan_one(rows,extract));self.assertEqual(len(index.available(rows)),1)
            self.assertFalse(index.scan_one(rows,extract));(root/'music/a.mp3').write_bytes(b'changed audio')
            self.assertFalse(index.available(rows));slot.acquire()
            self.assertFalse(index.scan_one(rows,extract));slot.release();self.assertTrue(index.scan_one(rows,extract));self.assertEqual(len(calls),2)

    def test_decoder_process_has_timeout_and_bounded_output_reads(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);path=root/'input.mp3';path.write_bytes(b'test')
            def runner(command,**kwargs):
                self.assertEqual(kwargs['timeout'],15);self.assertEqual(kwargs['stderr'],-3)
                folder=Path(kwargs['cwd']);(folder/'audio.pcm').write_bytes(b'\0\0'*(features.SAMPLE_RATE+1))
                (folder/'spectral.txt').write_bytes(b'x'*(features.MAX_META+1));return types.SimpleNamespace(returncode=0)
            with self.assertRaises(ValueError):features.analyze(path,180,root,runner)


if __name__=='__main__':unittest.main()
