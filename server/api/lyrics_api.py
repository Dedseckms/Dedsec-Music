# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded lazy lyrics: native embedded/sidecar first, exact LRCLIB fallback in one worker."""
import collections
import contextlib
import email.utils
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import re
import sqlite3
import threading
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request

import profile_api as profile

MAX_BODY=256*1024
MAX_TEXT=128*1024
MAX_LINES=2000
TTL={'ready':7*86400,'instrumental':7*86400,'not_found':6*3600,'error':300}
LRCLIB='https://lrclib.net/api'
EXTERNAL=urllib.request.build_opener(urllib.request.ProxyHandler({}),profile.NoRedirect())


def normalize(value):
    return ' '.join(unicodedata.normalize('NFKC',value).casefold().split())


def response(status,lines=None,synced=False,source=''):
    value={'status':status,'synced':synced,'lines':lines or [],'source':source,'revision':''}
    if status in ('ready','instrumental'):
        value['revision']=hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False).encode()).hexdigest()
    if len(json.dumps(value,ensure_ascii=False).encode())>MAX_TEXT:raise ValueError('Lyrics response limit')
    return value


def clean_text(value):
    if not isinstance(value,str) or len(value.encode())>MAX_TEXT:raise ValueError('Lyrics text limit')
    return ''.join(c for c in value if c in '\n\t' or ord(c)>=32 and ord(c)!=127)


def timed_lines(value,duration):
    """Only source LRC timestamps; never interpolate plain text."""
    text=clean_text(value);lines=[];offset=0;tag=re.compile(r'\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?\]')
    for raw in text.splitlines():
        off=re.fullmatch(r'\[offset:([+-]?\d{1,7})\]',raw.strip(),re.I)
        if off:offset=int(off[1]);continue
        tags=list(tag.finditer(raw))
        if not tags:continue
        lyric=raw[tags[-1].end():].strip()
        if len(lyric)>2000:raise ValueError('Lyrics line limit')
        for match in tags:
            if int(match[2])>=60:raise ValueError('Invalid timestamp')
            fraction=match[3] or '';milliseconds=int(fraction.ljust(3,'0')) if fraction else 0
            start=(int(match[1])*60+int(match[2]))*1000+milliseconds
            lines.append({'startMs':start,'text':lyric})
            if len(lines)>MAX_LINES:raise ValueError('Lyrics line count')
    for line in lines:
        line['startMs']+=offset
        if not 0<=line['startMs']<=duration*1000+2000:raise ValueError('Lyrics time outside song')
    lines.sort(key=lambda x:x['startMs'])
    return lines


def signature_matches(value,song,album=True):
    if not isinstance(value,dict):return False
    for field,key in [('trackName','title'),('artistName','artist')]+([('albumName','album')] if album else []):
        if not isinstance(value.get(field),str) or normalize(value[field])!=normalize(song[key]):return False
    duration=value.get('duration')
    return type(duration) in (int,float) and math.isfinite(duration) and abs(duration-song['duration'])<=2


def lrclib_result(value,song,album=True):
    if not isinstance(value,dict):raise ValueError('Invalid provider response')
    if not signature_matches(value,song,album):return response('not_found')
    if value.get('instrumental') is True:return response('instrumental',source='LRCLIB')
    if value.get('syncedLyrics'):
        lines=timed_lines(value['syncedLyrics'],song['duration'])
        if lines:return response('ready',lines,True,'LRCLIB')
    plain=value.get('plainLyrics')
    if plain:
        lines=[{'startMs':None,'text':line} for line in clean_text(plain).splitlines()]
        if len(lines)>MAX_LINES or any(len(x['text'])>2000 for x in lines):raise ValueError('Lyrics line limit')
        if any(x['text'].strip() for x in lines):return response('ready',lines,False,'LRCLIB')
    return response('not_found')


def search_result(values,song):
    if not isinstance(values,list) or len(values)>32:raise ValueError('Provider candidate limit')
    matching=[x for x in values if signature_matches(x,song,False)]
    confirmed=[x for x in matching if song['album'] and isinstance(x.get('albumName'),str) and normalize(x['albumName'])==normalize(song['album'])]
    chosen=confirmed or matching
    # Only one exact signature can compensate for absent or user-edited album tags.
    if len(chosen)!=1:return response('not_found')
    return lrclib_result(chosen[0],song,album=False)


def embedded_result(value,song):
    root=value.get('subsonic-response',{}) if isinstance(value,dict) else {}
    if not isinstance(root,dict):raise ValueError('Invalid native lyrics')
    if root.get('status')!='ok':
        code=root.get('error',{}).get('code')
        if code in (40,41,50):raise profile.ProfileError(401)
        return None
    listing=root.get('lyricsList',{})
    if not isinstance(listing,dict):raise ValueError('Invalid native lyrics')
    candidates=listing.get('structuredLyrics',[])
    if not isinstance(candidates,list) or len(candidates)>64:raise ValueError('Native lyrics limit')
    candidates=sorted((x for x in candidates if isinstance(x,dict)),key=lambda x:x.get('synced') is not True)
    for item in candidates:
        rows=item.get('line',[]);synced=item.get('synced') is True;offset=item.get('offset',0)
        if type(offset) is not int or abs(offset)>3_600_000 or not isinstance(rows,list) or len(rows)>MAX_LINES:raise ValueError('Native lyrics limit')
        lines=[]
        for row in rows:
            if not isinstance(row,dict):raise ValueError('Invalid native lyric')
            text=clean_text(row.get('value',''))
            if len(text)>2000:raise ValueError('Lyrics line limit')
            start=row.get('start')
            if synced:
                if type(start) is not int or not 0<=start+offset<=song['duration']*1000+2000:raise ValueError('Invalid native timestamp')
                start+=offset
            else:start=None
            lines.append({'startMs':start,'text':text})
        if any(row['text'].strip() for row in lines):
            if synced:lines.sort(key=lambda x:x['startMs'])
            return response('ready',lines,synced,'Navidrome')
    return None


def bounded_json(opener,request,timeout=4):
    deadline=time.monotonic()+10
    with opener.open(request,timeout=timeout) as incoming:
        data=bytearray();read=getattr(incoming,'read1',incoming.read)
        while True:
            if time.monotonic()>deadline:raise TimeoutError()
            block=read(min(8192,MAX_BODY+1-len(data)))
            if not block:break
            data.extend(block)
            if len(data)>MAX_BODY:raise ValueError('Provider response limit')
    return json.loads(data)


def native_lyrics(identity,song):
    body=urllib.parse.urlencode({'u':identity['username'],'jwt':identity['token'],'id':song['id'],
                                'v':'1.16.1','c':'DedSecMusicLyrics','f':'json'}).encode()
    request=urllib.request.Request(profile.NAV+'/rest/getLyricsBySongId.view',data=body,
                                    headers={'Content-Type':'application/x-www-form-urlencoded'},method='POST')
    try:return embedded_result(bounded_json(profile.HTTP,request),song)
    except urllib.error.HTTPError as error:
        if error.code in (401,403):raise profile.ProfileError(401) from None
        if error.code in (400,404,405,501):return None
        raise


def external_lyrics(song):
    if not all(song.get(k) and len(song[k])<=512 for k in ('title','artist')) or len(song.get('album',''))>512 or not 1<=song['duration']<=3600:return response('not_found')
    headers={'User-Agent':'DedSecMusic/1 (self-hosted lyrics)','Accept':'application/json'}
    if song.get('album'):
        query=urllib.parse.urlencode({'track_name':song['title'],'artist_name':song['artist'],'album_name':song['album'],'duration':song['duration']})
        request=urllib.request.Request(LRCLIB+'/get?'+query,headers=headers)
        try:
            value=lrclib_result(bounded_json(EXTERNAL,request),song)
            if value['status']!='not_found':return value
        except urllib.error.HTTPError as error:
            if error.code!=404:raise
        time.sleep(.25)
    query=urllib.parse.urlencode({'track_name':song['title'],'artist_name':song['artist']})
    request=urllib.request.Request(LRCLIB+'/search?'+query,headers=headers)
    try:return search_result(bounded_json(EXTERNAL,request),song)
    except urllib.error.HTTPError as error:
        if error.code==404:return response('not_found')
        raise


class Manager:
    def __init__(self,state,catalog,guard=None,start=True,native=native_lyrics,external=external_lyrics,protected_owner=None):
        self.protected_owner=protected_owner or os.environ.get('MUSIC_PROTECTED_OWNER_ID') or None
        if self.protected_owner is not None and not re.fullmatch(r'[A-Za-z0-9_-]{1,80}',self.protected_owner):raise ValueError('Invalid protected owner ID')
        self.catalog=catalog;self.guard=guard or (lambda identity:None);self.native=native;self.external=external
        self.folder=Path(state)/'lyrics';self.folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        if self.folder.resolve()!=self.folder or self.folder.is_symlink():raise ValueError('Physical lyrics cache required')
        self.path=self.folder/'cache.sqlite'
        if self.path.is_symlink():raise ValueError('Physical lyrics database required')
        self.lock=threading.RLock();self.stop=threading.Event();self.work=queue.Queue(maxsize=32);self.pending=set();self.limits={};self.thread=None
        self.external_retry=0
        with self.db() as db:
            db.execute('CREATE TABLE IF NOT EXISTS cache(key TEXT PRIMARY KEY,value TEXT NOT NULL,expires REAL NOT NULL,updated REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS timing(song TEXT PRIMARY KEY,version TEXT NOT NULL,revision TEXT NOT NULL,offset_ms INTEGER NOT NULL,updated REAL NOT NULL)')
        self.path.chmod(0o600);self.file_lock=None
        if start:
            import fcntl
            import stat
            fd=os.open(self.folder/'worker.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600);info=os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_nlink!=1:os.close(fd);raise ValueError('Private lyrics lock required')
            self.file_lock=os.fdopen(fd,'a')
            fcntl.flock(fd,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.thread=threading.Thread(target=self.loop,name='lyrics-fetch',daemon=True);self.thread.start()

    @contextlib.contextmanager
    def db(self):
        with contextlib.closing(sqlite3.connect(self.path,timeout=.2)) as db:
            with db:yield db

    def song(self,uid,key):
        with self.catalog.db() as db:
            row=db.execute('''SELECT m.id,m.library_id,m.path,substr(m.title,1,513) AS title,substr(m.artist,1,513) AS artist,
              substr(m.album,1,513) AS album,m.duration,m.updated_at FROM media_file m WHERE m.id=? AND m.missing=0
              AND EXISTS(SELECT 1 FROM user_library u WHERE u.user_id=? AND u.library_id=m.library_id)''',(key,uid)).fetchone()
        if row is None:raise profile.ProfileError(404)
        song=dict(row);physical=self.catalog.physical(song)
        if physical is None:raise profile.ProfileError(404)
        for name in ('title','artist','album'):song[name]=song[name] or ''
        if type(song['duration']) not in (int,float) or not math.isfinite(song['duration']) or song['duration']<=0:raise profile.ProfileError(404)
        # Scanner metadata plus current physical signature invalidates replaced/tagged files.
        key=hashlib.sha256(json.dumps([song,physical[1],physical[2]],sort_keys=True,separators=(',',':')).encode()).hexdigest()
        return song,key

    def get(self,identity,key):
        self.guard(identity);song,version=self.song(identity['id'],key)
        now=time.time()
        with self.lock:
            times=self.limits.setdefault(identity['id'],collections.deque())
            while times and times[0]<now-60:times.popleft()
            if len(times)>=60:raise profile.ProfileError(429)
            times.append(now)
            self.limits={uid:values for uid,values in self.limits.items() if values and values[-1]>=now-60}
            if len(self.limits)>1000:raise profile.ProfileError(429)
            # Visibility was checked before looking at a global content cache.
            with self.db() as db:row=db.execute('SELECT value FROM cache WHERE key=? AND expires>?',(version,now)).fetchone()
            if row:return self.decorate(identity,key,version,json.loads(row[0]))
            if version not in self.pending:
                if self.work.full():return self.decorate(identity,key,version,response('error'))
                self.pending.add(version);self.work.put_nowait((identity.copy(),song,version,now))
        return self.decorate(identity,key,version,response('pending'))

    def decorate(self,identity,key,version,value):
        # Adjustments are separate from provider data and never rewrite source timestamps.
        value=dict(value);value['timingOffsetMs']=0
        value['canEditTiming']=bool(self.protected_owner and identity['id']==self.protected_owner and value.get('synced') and value.get('status')=='ready')
        with self.db() as db:
            row=db.execute('SELECT offset_ms FROM timing WHERE song=? AND version=? AND revision=?',(key,version,value.get('revision',''))).fetchone()
        if row:value['timingOffsetMs']=row[0]
        return value

    def set_timing(self,identity,key,offset,revision):
        self.guard(identity)
        # Promoted administrators cannot change the owner's library calibration.
        if not self.protected_owner or identity['id']!=self.protected_owner:raise profile.ProfileError(403)
        if type(offset) is not int or not -10000<=offset<=10000 or not isinstance(revision,str) or not re.fullmatch(r'[a-f0-9]{64}',revision):raise profile.ProfileError(400)
        song,version=self.song(identity['id'],key)
        with self.lock:
            with self.db() as db:
                db.execute('BEGIN IMMEDIATE')
                row=db.execute('SELECT value FROM cache WHERE key=? AND expires>?',(version,time.time())).fetchone()
                if not row:raise profile.ProfileError(409)
                value=json.loads(row[0])
                if value.get('status')!='ready' or not value.get('synced') or value.get('revision')!=revision:raise profile.ProfileError(409)
                self.guard(identity)
                if self.song(identity['id'],key)[1]!=version:raise profile.ProfileError(409)
                db.execute('INSERT OR REPLACE INTO timing VALUES(?,?,?,?,?)',(key,version,revision,offset,time.time()))
            return self.decorate(identity,key,version,value)

    def process(self,item):
        identity,song,key,queued=item
        try:
            if time.time()-queued>120:return
            self.guard(identity)
            _,current=self.song(identity['id'],song['id'])
            if current!=key:return
            value=self.native(identity,song)
            if value is None:value=response('error') if time.time()<self.external_retry else self.external(song)
            self.guard(identity)
            _,current=self.song(identity['id'],song['id'])
            if current!=key:return
        except urllib.error.HTTPError as error:
            if error.code==429:
                try:
                    value=(error.headers or {}).get('Retry-After','60')
                    if len(value)>128:raise ValueError()
                    delay=int(value) if value.isdigit() else email.utils.parsedate_to_datetime(value).timestamp()-time.time()
                    delay=max(1,delay)
                except (ValueError,TypeError,OverflowError):delay=60
                self.external_retry=time.time()+delay
            value=response('error')
        except profile.ProfileError:return
        except (OSError,ValueError,TypeError,KeyError,sqlite3.Error):value=response('error')
        finally:
            identity.clear()
        with self.db() as db:
            stamp=time.time();db.execute('INSERT OR REPLACE INTO cache VALUES(?,?,?,?)',(key,json.dumps(value,separators=(',',':')),stamp+TTL[value['status']],stamp))
            while True:
                count,size=db.execute('SELECT count(*),coalesce(sum(length(value)),0) FROM cache').fetchone()
                if count<=2048 and size<=64*2**20:break
                db.execute('DELETE FROM cache WHERE key=(SELECT key FROM cache ORDER BY updated LIMIT 1)')

    def loop(self):
        while not self.stop.is_set():
            try:item=self.work.get(timeout=.5)
            except queue.Empty:continue
            try:self.process(item)
            except (OSError,ValueError,sqlite3.Error):pass
            finally:
                with self.lock:self.pending.discard(item[2])
                self.work.task_done()
            self.stop.wait(.5)

    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=20)
        if self.file_lock and (self.thread is None or not self.thread.is_alive()):self.file_lock.close()
        while not self.work.empty():
            try:self.work.get_nowait()[0].clear();self.work.task_done()
            except queue.Empty:break
