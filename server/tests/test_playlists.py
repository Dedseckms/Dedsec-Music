# SPDX-License-Identifier: GPL-3.0-or-later
import copy
import json
import sys
from pathlib import Path
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
import test_importer as fixtures
from test_importer import FakeProvider
import import_playlists as playlists
from import_errors import ImportFailure


class PlaylistProvider(FakeProvider):
    def inventory(self):
        self.playlists=[{'key':'first','name':'Original title','ids':['2','1','2','3']}]
        return super().inventory()


class FakeNative:
    rows={};indexed=True;lost_create=False;lost_replace=False;replacements=0
    def __init__(self,credentials,event):self.uid=credentials['uid']
    def songs(self,paths):return {path:'song-'+Path(path).stem for path in paths} if self.indexed else {}
    def state(self,key):return copy.deepcopy(self.rows[key])
    def lookup(self,comment):
        rows=[copy.deepcopy(s) for s in self.rows.values() if s['meta']['comment']==comment and s['meta']['ownerId']==self.uid]
        return rows[0] if len(rows)==1 else None
    def create(self,name,comment):
        key='playlist-'+str(len(self.rows))
        state={'meta':{'id':key,'ownerId':self.uid,'public':False,'sync':False,'name':name,'comment':comment,'path':'','rules':None},'ids':[]}
        self.rows[key]=state
        if self.lost_create:raise ImportFailure('NETWORK_ERROR')
        return copy.deepcopy(state)
    def replace(self,key,ids):
        type(self).replacements+=1;self.rows[key]['ids']=list(ids)
        if self.lost_replace:raise ImportFailure('NETWORK_ERROR')


class PlaylistTests(unittest.TestCase):
    def setUp(self):
        FakeNative.rows={};FakeNative.indexed=True;FakeNative.lost_create=False;FakeNative.lost_replace=False;FakeNative.replacements=0
        self.harness=fixtures.ImportTests()

    def prepare(self,m):m.provider_factory=PlaylistProvider;m.playlist_client=FakeNative

    def test_global_skip_still_enters_private_per_user_lists_with_order_and_repeats(self):
        with self.harness.manager() as m:
            self.prepare(m);first=self.harness.schedule(m);m.run_job(first)
            second=self.harness.schedule(m,{'id':'user-b','token':'private-native-b'});m.run_job(second)
            self.assertEqual(m.view('user-b',second)['skipped'],2)
            self.assertEqual(len(FakeNative.rows),2)
            for row in FakeNative.rows.values():
                self.assertFalse(row['meta']['public']);self.assertEqual(row['ids'],['song-2','song-1','song-2'])
            self.assertEqual({r['meta']['ownerId'] for r in FakeNative.rows.values()},{'user-a','user-b'})

    def test_indexing_yields_and_keeps_credentials_only_in_memory_until_ready(self):
        with self.harness.manager() as m:
            self.prepare(m);FakeNative.indexed=False;key=self.harness.schedule(m);m.run_job(key)
            value=m.view('user-a',key)
            self.assertEqual((value['state'],value['phase'],value['pendingPlaylistCount']),('queued','indexing',1))
            self.assertIn(key,m.credentials);self.assertFalse(FakeNative.rows)
            FakeNative.indexed=True;m.run_job(key)
            value=m.view('user-a',key)
            self.assertEqual((value['state'],value['completedPlaylistCount'],value['processed']),('completed',1,2))
            self.assertEqual(FakeProvider.calls,['1','2']);self.assertFalse(m.credentials)

    def test_lost_create_and_replace_responses_resume_without_duplicate_list_or_append(self):
        for failure in ['lost_create','lost_replace']:
            FakeNative.rows={};FakeNative.lost_create=False;FakeNative.lost_replace=False
            with self.harness.manager() as m:
                self.prepare(m);setattr(FakeNative,failure,True);key=self.harness.schedule(m);m.run_job(key)
                self.assertEqual(m.view('user-a',key)['state'],'failed')
                setattr(FakeNative,failure,False);self.harness.schedule(m,key=key);m.run_job(key)
                self.assertEqual(m.view('user-a',key)['state'],'completed');self.assertEqual(len(FakeNative.rows),1)
                self.assertEqual(next(iter(FakeNative.rows.values()))['ids'],['song-2','song-1','song-2'])

    def test_manual_content_or_metadata_edits_fail_closed_on_next_import(self):
        for kind in ['ids','name','public']:
            FakeNative.rows={}
            with self.harness.manager() as m:
                self.prepare(m);key=self.harness.schedule(m);m.run_job(key)
                row=next(iter(FakeNative.rows.values()))
                if kind=='ids':row['ids'].append('manually-added')
                else:row['meta'][kind]='User title' if kind=='name' else True
                before=copy.deepcopy(row);count=FakeNative.replacements
                key=self.harness.schedule(m);m.run_job(key)
                self.assertEqual(m.view('user-a',key)['state'],'failed')
                self.assertEqual(next(iter(FakeNative.rows.values())),before);self.assertEqual(FakeNative.replacements,count)

    def test_unavailable_audio_reports_missing_in_playlist_and_finished_with_errors(self):
        with self.harness.manager() as m:
            self.prepare(m);FakeProvider.failure='SOURCE_UNAVAILABLE';key=self.harness.schedule(m);m.run_job(key)
            result=m.view('user-a',key)
            self.assertEqual((result['state'],result['missingPlaylistTracks']),('finished_with_errors',1))
            self.assertEqual(next(iter(FakeNative.rows.values()))['ids'],['song-2','song-2'])

    def test_native_path_mapping_rejects_ambiguous_and_other_library(self):
        native=object.__new__(playlists.Native)
        native.pages=lambda path:[{'id':'a','libraryId':2,'path':'yandex/1.mp3'},
            {'id':'b','libraryId':1,'path':'uploads/1.mp3'},{'id':'right','libraryId':1,'path':'yandex/1.mp3'}]
        self.assertEqual(native.songs(['yandex/1.mp3']),{'yandex/1.mp3':'right'})
        native.pages=lambda path:[{'id':value,'libraryId':1,'path':'yandex/1.mp3'} for value in ['a','b']]
        with self.assertRaises(ImportFailure):native.songs(['yandex/1.mp3'])


if __name__=='__main__':unittest.main()
