# SPDX-License-Identifier: GPL-3.0-or-later
"""Private source playlists with durable intents and strict native snapshot guards.

Navidrome has no atomic If-Match for playlist contents. Do not edit imported lists
while an import runs; observed manual changes stop reconciliation without overwrite.
"""
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.sax

import profile_api as profile
from import_errors import ImportFailure, check_cancel

LIBRARY_ID=os.environ.get('IMPORT_LIBRARY_ID','1')
FIELDS=('id','name','comment','ownerId','public','sync','path','rules','createdAt','uploadedImage','externalImageUrl')


def native(token,path,payload=None,method=None,cap=4*2**20):
    request=urllib.request.Request(profile.NAV+path,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={'X-ND-Authorization':'Bearer '+token,'Content-Type':'application/json'},method=method)
    try:
        with profile.HTTP.open(request,timeout=20) as response:
            raw=response.read(cap+1)
            if len(raw)>cap:raise ImportFailure('SOURCE_LIMIT')
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise ImportFailure('AUTH_REQUIRED' if error.code in (401,403) else 'SOURCE_UNAVAILABLE') from None
    except (OSError,ValueError):raise ImportFailure('NETWORK_ERROR') from None


class Native:
    def __init__(self,credentials,event):
        self.token=credentials['native'];self.uid=credentials['uid'];self.event=event
        user,_=profile.native(self.token,'/api/user/'+self.uid)
        if user.get('id')!=self.uid or not isinstance(user.get('userName'),str):raise ImportFailure('AUTH_REQUIRED')
        self.username=user['userName']

    def pages(self,path,limit=100_000):
        count=0
        for offset in range(0,limit+1,1000):
            check_cancel(self.event)
            rows=native(self.token,path+('?'+urllib.parse.urlencode({'_start':offset,'_end':offset+1000,'_sort':'id','_order':'ASC'})))
            if not isinstance(rows,list) or len(rows)>1000:raise ImportFailure('SOURCE_UNAVAILABLE')
            for row in rows:
                count+=1
                if count>limit:raise ImportFailure('SOURCE_LIMIT')
                yield row
            if len(rows)<1000:return
        raise ImportFailure('SOURCE_LIMIT')

    def songs(self,paths):
        """Match an exact physical download path in the configured library only."""
        wanted=set(paths);found={};ambiguous=set()
        for row in self.pages('/api/song'):
            path=row.get('path')
            if str(row.get('libraryId'))!=LIBRARY_ID or path not in wanted:continue
            key=row.get('id')
            if not isinstance(key,str) or not key:raise ImportFailure('SOURCE_UNAVAILABLE')
            if path in found:ambiguous.add(path)
            found[path]=key
        if ambiguous:raise ImportFailure('SOURCE_UNAVAILABLE')
        return found

    def state(self,key):
        check_cancel(self.event)
        row=native(self.token,'/api/playlist/'+urllib.parse.quote(key,safe=''))
        if not isinstance(row,dict) or row.get('ownerId')!=self.uid:raise ImportFailure('SOURCE_UNAVAILABLE')
        rows=self.pages('/api/playlist/'+urllib.parse.quote(key,safe='')+'/tracks',50_000)
        ids=[r.get('mediaFileId') for r in rows]
        if any(not isinstance(value,str) or not value for value in ids):raise ImportFailure('SOURCE_UNAVAILABLE')
        return {'meta':{field:row.get(field) for field in FIELDS},'ids':ids}

    def lookup(self,comment):
        matches=[row for row in self.pages('/api/playlist',10_000)
            if row.get('comment')==comment and row.get('ownerId')==self.uid]
        if len(matches)>1:raise ImportFailure('SOURCE_UNAVAILABLE')
        return self.state(matches[0]['id']) if matches else None

    def create(self,name,comment):
        row=native(self.token,'/api/playlist',{'name':name,'comment':comment,'public':False,'sync':False},'POST')
        if not isinstance(row,dict) or not isinstance(row.get('id'),str):raise ImportFailure('SOURCE_UNAVAILABLE')
        return self.state(row['id'])

    def replace(self,key,ids):
        check_cancel(self.event)
        body=urllib.parse.urlencode({'u':self.username,'jwt':self.token,'v':'1.16.1','c':'DedSecMusicImport',
            'f':'xml','playlistId':key,'songId':ids},doseq=True).encode()
        request=urllib.request.Request(profile.NAV+'/rest/createPlaylist.view',data=body,
            headers={'Content-Type':'application/x-www-form-urlencoded'},method='POST')
        class Result(xml.sax.handler.ContentHandler):
            ok=False;playlist=None
            def startElement(self,name,attrs):
                if name=='subsonic-response':self.ok=attrs.get('status')=='ok'
                if name=='playlist':self.playlist=attrs.get('id')
        result=Result();parser=xml.sax.make_parser()
        parser.setFeature(xml.sax.handler.feature_external_ges,False)
        parser.setFeature(xml.sax.handler.feature_external_pes,False);parser.setContentHandler(result)
        try:
            with profile.HTTP.open(request,timeout=30) as response:
                total=0
                while True:
                    block=response.read(65536)
                    if not block:break
                    check_cancel(self.event);total+=len(block)
                    if total>32*2**20:raise ImportFailure('SOURCE_LIMIT')
                    parser.feed(block)
                parser.close()
        except urllib.error.HTTPError as error:
            raise ImportFailure('AUTH_REQUIRED' if error.code in (401,403) else 'SOURCE_UNAVAILABLE') from None
        except (OSError,xml.sax.SAXException):raise ImportFailure('NETWORK_ERROR') from None
        if not result.ok or result.playlist!=key:raise ImportFailure('SOURCE_UNAVAILABLE')


def safe(state,uid,comment,name=None):
    meta=state['meta']
    if (meta.get('ownerId')!=uid or meta.get('public') is not False or meta.get('sync') is not False
            or meta.get('path') not in (None,'') or meta.get('rules') not in (None,{},'')
            or meta.get('comment')!=comment or (name is not None and meta.get('name')!=name)):
        raise ImportFailure('SOURCE_UNAVAILABLE')


def reconcile(manager,key,credentials,event,client_factory=Native):
    """Return False while the scanner has not indexed all successfully committed files."""
    with manager.db() as db:
        sections=[dict(row) for row in db.execute('SELECT * FROM playlists WHERE job=? ORDER BY rowid',(key,))]
        items={row['id']:dict(row) for row in db.execute('SELECT * FROM items WHERE job=?',(key,))}
        paths={row['id']:row['path'] for row in db.execute('SELECT * FROM downloads')}
    eligible=[];required={};missing=0
    for section in sections:
        music=[value for value in json.loads(section['ids']) if items[value]['kind']=='music']
        if not music:
            with manager.db() as db:db.execute("UPDATE playlists SET status='excluded',missing=0 WHERE job=? AND source_key=?",(key,section['source_key']))
            continue
        available=[value for value in music if items[value]['outcome'] in ('downloaded','skipped')]
        for value in available:
            if value not in paths:raise ImportFailure('SOURCE_UNAVAILABLE')
            required[value]=paths[value]
        missing+=len(music)-len(available)
        eligible.append((section,available,len(music)-len(available)))
    if not eligible:return True
    manager.update(key,phase='indexing',current=None)
    client=client_factory(credentials,event);song_ids=client.songs(required.values())
    if any(path not in song_ids for path in required.values()):return False
    manager.update(key,phase='playlists')
    for section,available,absent in eligible:
        check_cancel(event)
        desired=[song_ids[required[value]] for value in available]
        # Do not create an empty list containing only unavailable source music.
        if not desired:
            with manager.db() as db:db.execute("UPDATE playlists SET status='unavailable',missing=? WHERE job=? AND source_key=?",(absent,key,section['source_key']))
            continue
        source_key=section['source_key'];uid=credentials['uid']
        with manager.db() as db:binding=db.execute('SELECT * FROM bindings WHERE uid=? AND source_key=?',(uid,source_key)).fetchone()
        if binding is None:
            comment='Imported from Yandex Music; DedSec Music '+str(uuid.uuid4())
            with manager.db() as db:
                db.execute('INSERT INTO bindings(uid,source_key,comment,name,creating) VALUES(?,?,?,?,0)',(uid,source_key,comment,section['name']))
                binding=db.execute('SELECT * FROM bindings WHERE uid=? AND source_key=?',(uid,source_key)).fetchone()
        comment=binding['comment'];old=json.loads(binding['snapshot']) if binding['snapshot'] else None
        intent=json.loads(binding['intent']) if binding['intent'] else None
        if old is None:
            state=client.lookup(comment)
            if state is None:
                # A lost create response is uncertain; never create a duplicate blindly.
                if binding['creating']:raise ImportFailure('SOURCE_UNAVAILABLE')
                with manager.db() as db:db.execute('UPDATE bindings SET creating=1 WHERE uid=? AND source_key=?',(uid,source_key))
                state=client.create(binding['name'],comment)
            safe(state,uid,comment,binding['name'])
            if state['ids']:raise ImportFailure('SOURCE_UNAVAILABLE')
            old=state
            with manager.db() as db:db.execute('UPDATE bindings SET snapshot=?,creating=0 WHERE uid=? AND source_key=?',(json.dumps(old),uid,source_key))
        state=client.state(old['meta']['id']);safe(state,uid,comment)
        if state!=old and state!=intent:raise ImportFailure('SOURCE_UNAVAILABLE')
        # A source title change never renames a user's existing list implicitly.
        target={'meta':old['meta'],'ids':desired}
        if state!=target:
            with manager.db() as db:db.execute('UPDATE bindings SET intent=? WHERE uid=? AND source_key=?',(json.dumps(target),uid,source_key))
            # Re-read directly before the native atomic replacement.
            if client.state(old['meta']['id'])!=state:raise ImportFailure('SOURCE_UNAVAILABLE')
            client.replace(old['meta']['id'],desired)
            state=client.state(old['meta']['id'])
            if state!=target:raise ImportFailure('SOURCE_UNAVAILABLE')
        with manager.db() as db:
            db.execute('UPDATE bindings SET snapshot=?,intent=NULL WHERE uid=? AND source_key=?',(json.dumps(target),uid,source_key))
            db.execute("UPDATE playlists SET status='completed',missing=? WHERE job=? AND source_key=?",(absent,key,source_key))
    return True
