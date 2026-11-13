# SPDX-License-Identifier: GPL-3.0-or-later
"""Per-account radio: whole visible catalogue, strict moods, real incremental features."""
import collections
import contextlib
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import sqlite3
import threading
import time
import urllib.parse

import profile_api as profile
from radio_catalog import Catalog,dto,epoch
from radio_features import Index

PREFIX='/dedsec/profile/v1/radio/'
MOODS={'ANY','ENERGY','CHILL','FOCUS','WORKOUT','PARTY','SAD','HAPPY'}
MODES={'BALANCED','FAVORITES','DISCOVERY'}
ENGINE=None
INIT_LOCK=threading.Lock()


class Rejected(Exception):
    def __init__(self,status):self.status=status


def identifier(value):
    return isinstance(value,str) and 1<=len(value)<=160 and all(c.isascii() and (c.isalnum() or c in '_-') for c in value)


def integer(value,low,high):return type(value) is int and low<=value<=high


def request_mix(value):
    if not isinstance(value,dict) or set(value)-{'mood','mode','size','history','dislikedIds','excludeIds'}:raise Rejected(400)
    mood=value.get('mood','ANY');mode=value.get('mode','BALANCED');size=value.get('size',40)
    if not isinstance(mood,str) or mood not in MOODS or not isinstance(mode,str) or mode not in MODES or not integer(size,1,60):raise Rejected(400)
    history=value.get('history',[]);disliked=value.get('dislikedIds',[]);excluded=value.get('excludeIds',[])
    if not isinstance(history,list) or len(history)>200 or not isinstance(disliked,list) or len(disliked)>1000 or not isinstance(excluded,list) or len(excluded)>100:raise Rejected(400)
    if any(not identifier(key) for key in disliked+excluded):raise Rejected(400)
    stamp=int(time.time()*1000)
    for item in history:
        if (not isinstance(item,dict) or set(item)!={'id','playedAt','count'} or not identifier(item['id'])
                or not integer(item['playedAt'],0,stamp+300_000) or not integer(item['count'],0,100_000)):raise Rejected(400)
    return {'mood':mood,'mode':mode,'size':size,'history':history,'dislikedIds':disliked,'excludeIds':excluded}


def vector(features):
    clip=lambda n:max(0,min(1,n))
    loud=clip((features['rms']+35)/28);bright=clip(features['centroid']/3000)
    motion=clip(math.log1p(max(0,features['flux']))/math.log1p(10));tempo=features['tempo'] if features['tempoConfidence']>=.18 else 0
    energy=.60*loud+.15*bright+.15*motion+.10*clip(features['zcr']/.25)
    return energy,bright,motion,tempo


def eligible(song,features,mood):
    if mood=='ANY':return True
    if features is None:return False
    energy,bright,motion,tempo=vector(features)
    genre=' '.join(song['genres']).lower().replace('-','').replace(' ','')
    has=lambda words:any(word in genre for word in words)
    if mood=='ENERGY':return energy>=.64 and (tempo>=105 or motion>=.3)
    if mood=='WORKOUT':return energy>=.66 and tempo>=110
    if mood=='PARTY':return has(['dance','house','disco','pop','funk','electro','edm','techno']) and energy>=.58 and tempo>=105
    if mood=='CHILL':return energy<=.52 and (not tempo or tempo<=110)
    if mood=='FOCUS':return has(['ambient','instrumental','classical','jazz','lofi','relax','soundtrack','newage']) and energy<=.56 and motion<=.6 and (not tempo or tempo<=125)
    if mood=='HAPPY':return has(['pop','dance','disco','funk','ska','reggae','house']) and energy>=.5 and bright>=.25 and (tempo>=90 or motion>=.35)
    if mood=='SAD':return has(['blues','soul','indie','alternative','folk','acoustic','classical','singersongwriter']) and energy<=.55 and bright<=.6 and (not tempo or tempo<=110)
    return False


def artist(song):return song.get('artist_id') or song.get('artist') or song['id']


class Engine:
    def __init__(self,catalog,index,folder):
        self.catalog=catalog;self.index=index;self.path=Path(folder)/'feedback.sqlite'
        if self.path.is_symlink():raise ValueError('Physical radio feedback required')
        self.lock=threading.RLock();self.mix_slot=threading.BoundedSemaphore(1);self.limits={}
        with self.db() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS feedback(uid TEXT NOT NULL,id TEXT NOT NULL,skips INTEGER NOT NULL DEFAULT 0,
                plays INTEGER NOT NULL DEFAULT 0,disliked INTEGER NOT NULL DEFAULT 0,updated INTEGER NOT NULL,last_kind TEXT,last_event INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uid,id));
                CREATE TABLE IF NOT EXISTS served(uid TEXT NOT NULL,id TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(uid,id));''')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock,contextlib.closing(sqlite3.connect(self.path,timeout=.5)) as db:
            db.row_factory=sqlite3.Row
            with db:yield db

    def rate(self,uid,kind,maximum):
        with self.lock:
            now=time.monotonic();key=(uid,kind)
            self.limits={k:v for k,v in self.limits.items() if v and v[-1]>now-60}
            times=self.limits.setdefault(key,collections.deque())
            while times and times[0]<now-60:times.popleft()
            if len(times)>=maximum or len(self.limits)>1000:raise Rejected(429)
            times.append(now)

    def mix(self,uid,request):
        self.rate(uid,'mix',10)
        if not self.mix_slot.acquire(blocking=False):raise Rejected(429)
        try:return self._mix(uid,request)
        finally:self.mix_slot.release()

    def _mix(self,uid,request):
        songs=self.catalog.songs(uid);features=self.index.available(songs);now=int(time.time()*1000)
        with self.db() as db:
            feedback={row['id']:dict(row) for row in db.execute('SELECT * FROM feedback WHERE uid=?',(uid,))}
            served={row['id']:row['at'] for row in db.execute('SELECT id,at FROM served WHERE uid=? AND at>?',(uid,now-21600_000))}
        history={item['id']:item for item in request['history'] if item['id'] in songs}
        denied=set(request['dislikedIds'])|set(request['excludeIds'])|{key for key,row in feedback.items() if row['disliked']}
        genres=collections.Counter();artists=collections.Counter();known={}
        for key,song in songs.items():
            own=feedback.get(key,{});client=history.get(key,{})
            count=max(int(song.get('play_count') or 0),client.get('count',0))+own.get('plays',0)
            known[key]=count
            weight=(8 if song.get('starred') else 0)+min(4,math.log2(1+count))-.8*min(5,own.get('skips',0))
            if key in denied:weight=-3
            for genre in song['genres']:genres[genre]+=weight/max(1,len(song['genres']))
            artists[artist(song)]+=weight
        genre_peak=max(1,max(genres.values(),default=1));artist_peak=max(1,max(artists.values(),default=1))
        candidates=[];pending=[]
        for key,song in songs.items():
            if key in denied or request['mode']=='FAVORITES' and not song.get('starred'):continue
            last=max(epoch(song.get('play_date')),history.get(key,{}).get('playedAt',0))
            if last>now-30*60_000 or served.get(key,0)>now-5*60_000:continue
            if key not in features:pending.append(key)
            if not eligible(song,features.get(key),request['mood']):continue
            taste=sum(genres[g]/genre_peak for g in song['genres'])/max(1,len(song['genres']))
            score=3*taste+1.5*artists[artist(song)]/artist_peak+(2 if song.get('starred') else 0)
            score-=min(4,feedback.get(key,{}).get('skips',0))
            if request['mode']=='DISCOVERY':score-=50 if known[key] else 0
            else:score-=min(2,math.log2(1+known[key])*.3)
            variation=int.from_bytes(hashlib.sha256((uid+'|'+str(now//3600_000)+'|'+key).encode()).digest()[:4],'big')/2**32
            candidates.append((score+variation*.03,key))
        def initial_taste(key):
            song=songs[key]
            return (8 if song.get('starred') else 0)+min(4,math.log2(1+known[key]))+sum(genres[g]/genre_peak for g in song['genres'])
        self.index.prioritize([key for _,key in heapq.nlargest(100,candidates)]+heapq.nlargest(100,pending,key=initial_taste))
        # Rank the entire library, then sequence a bounded top band plus artist diversity.
        band=heapq.nlargest(max(120,request['size']*10),candidates)
        by_artist={}
        for item in candidates:
            key=artist(songs[item[1]])
            if key not in by_artist or item>by_artist[key]:by_artist[key]=item
        band.extend(heapq.nlargest(60,by_artist.values()))
        result=[];remaining=dict((key,score) for score,key in band)
        while remaining and len(result)<request['size']:
            alternative=bool(result and any(artist(songs[key])!=artist(songs[result[-1]]) for key in remaining))
            def rank(key):
                value=remaining[key]
                if result:
                    if alternative and artist(songs[key])==artist(songs[result[-1]]):return -1e9
                    if key in features and result[-1] in features:
                        a,b=vector(features[key]),vector(features[result[-1]])
                        value-=sum(abs(a[i]-b[i]) for i in range(3))*.5
                        if a[3] and b[3]:value-=min(1,abs(a[3]-b[3])/50)
                return value
            key=max(remaining,key=rank);result.append(key);del remaining[key]
        with self.db() as db:
            db.execute('DELETE FROM served WHERE uid=? AND at<?',(uid,now-21600_000))
            db.executemany('INSERT OR REPLACE INTO served VALUES(?,?,?)',[(uid,key,now) for key in result])
        reason='ANALYZING' if pending and (request['mood']!='ANY' or result) else None
        if not result and reason is None:reason='NO_MATCHES'
        return {'songs':[dto(songs[key]) for key in result],'analysis':{'indexed':len(features),'total':len(songs)},
            'reason':reason,'mood':request['mood'],'mode':request['mode']}

    def event(self,uid,value):
        self.rate(uid,'events',60)
        if (not isinstance(value,dict) or set(value)!={'id','kind','positionMs','durationMs'} or not identifier(value['id'])
                or value['kind'] not in ('skipped','played','disliked') or not integer(value['positionMs'],0,86400_000)
                or not integer(value['durationMs'],0,86400_000)):raise Rejected(400)
        song=self.catalog.song(uid,value['id'])
        if song is None:raise Rejected(404)
        now=int(time.time()*1000);kind=value['kind'];duration=max(0,float(song['duration']))*1000;position=value['positionMs']
        skips=int(kind=='skipped' and 5000<=position<duration*.8)
        plays=int(kind=='played' and position>=min(30_000,duration*.5) and duration>0)
        with self.db() as db:
            row=db.execute('SELECT * FROM feedback WHERE uid=? AND id=?',(uid,value['id'])).fetchone()
            if row and row['last_kind']==kind and row['last_event']>now-30_000:return {'ok':True}
            if row is None and db.execute('SELECT count(*) FROM feedback WHERE uid=?',(uid,)).fetchone()[0]>=5000:raise Rejected(429)
            db.execute('INSERT OR IGNORE INTO feedback(uid,id,updated) VALUES(?,?,?)',(uid,value['id'],now))
            db.execute('UPDATE feedback SET skips=min(100,skips+?),plays=min(10000,plays+?),disliked=max(disliked,?),updated=?,last_kind=?,last_event=? WHERE uid=? AND id=?',
                (skips,plays,int(kind=='disliked'),now,kind,now,uid,value['id']))
        return {'ok':True}


def configure(database,roots,state,slot=None,start=True):
    global ENGINE
    with INIT_LOCK:
        if ENGINE is not None:return ENGINE
        catalog=Catalog(database,roots);folder=Path(state)/'radio';folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        index=Index(folder,catalog,slot,start=start);ENGINE=Engine(catalog,index,folder);return ENGINE


def from_environment(slot=None):
    database=os.environ.get('NAV_DB_PATH')
    if not database:return None
    roots=json.loads(os.environ.get('RADIO_LIBRARY_MAP','{"1":"/storage/library"}'))
    return configure(database,roots,os.environ.get('STATE_ROOT','/state'),slot)


def dispatch(handler,chunks):
    parsed=urllib.parse.urlsplit(handler.path)
    if not parsed.path.startswith(PREFIX):return None
    try:
        if parsed.query:raise Rejected(400)
        if parsed.path not in (PREFIX+'mix',PREFIX+'events'):raise Rejected(404)
        if handler.command!='POST':raise Rejected(405)
        identity=profile.authenticate(handler.headers)
        engine=ENGINE
        if engine is None:raise Rejected(503)
        data=bytearray()
        for block in chunks(handler.rfile,handler.headers):
            if len(data)+len(block)>64*1024:raise Rejected(413)
            data.extend(block)
        try:value=json.loads(data)
        except (ValueError,UnicodeError):raise Rejected(400) from None
        result=engine.mix(identity['id'],request_mix(value)) if parsed.path.endswith('/mix') else engine.event(identity['id'],value)
        if len(json.dumps(result,separators=(',',':')).encode())>512*1024:raise Rejected(503)
        profile.send(handler,200,result);return 200
    except (Rejected,profile.ProfileError) as error:
        profile.send(handler,error.status,{'error':'radio_request_rejected'});return error.status
    except (OSError,ValueError,KeyError,TypeError,sqlite3.Error):
        profile.send(handler,503,{'error':'radio_unavailable'});return 503
