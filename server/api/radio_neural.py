# SPDX-License-Identifier: GPL-3.0-or-later
"""Optional offline YAMNet descriptors. Playback reads cached data only."""
import base64
import array
import contextlib
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import secrets
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time

MODEL_SHA='10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de'
MODEL='yamnet521-'+MODEL_SHA
SAMPLES=15600*4
WORKER_ROUTE='/dedsec/profile/v1/radio/worker/'


def unit(values):
    if not isinstance(values,list) or len(values)!=521 or any(type(v) not in (int,float) or not math.isfinite(v) or not 0<=v<=1 for v in values):
        raise ValueError('Invalid neural descriptor')
    norm=math.sqrt(sum(v*v for v in values))
    return array.array('f',(v/norm for v in values)) if norm>1e-9 else None


def affinity(vectors,weights):
    """At most64 positive/negative examples, selected within the caller's visible songs."""
    def centroid(sign):
        selected=sorted(((abs(w),key) for key,w in weights.items() if w*sign>0 and key in vectors),reverse=True)[:64]
        total=[0.0]*521
        for weight,key in selected:
            for i,v in enumerate(vectors[key]):total[i]+=v*min(8,weight)
        norm=math.sqrt(sum(v*v for v in total))
        return [v/norm for v in total] if norm>1e-9 else None
    positive,negative=centroid(1),centroid(-1)
    return {key:3*sum(a*b for a,b in zip(vector,positive or []))-2*sum(a*b for a,b in zip(vector,negative or [])) for key,vector in vectors.items()}


class Cache:
    def __init__(self,folder,catalog,slot=None):
        self.folder=Path(folder);self.catalog=catalog;self.slot=slot
        self.path=self.folder/'neural.sqlite';self.token_path=self.folder/'worker.token'
        self.lock=threading.RLock();self.lease_lock=threading.Lock();self.priority=[];self.lease=None;self.last_lease=0
        if self.folder.resolve()!=self.folder or self.path.is_symlink():raise ValueError('Physical neural cache required')
        with self.db() as db:db.execute('CREATE TABLE IF NOT EXISTS neural(id TEXT PRIMARY KEY,signature TEXT NOT NULL,model TEXT NOT NULL,value TEXT,updated REAL NOT NULL)')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def db(self):
        with self.lock,contextlib.closing(sqlite3.connect(self.path,timeout=.2)) as db:
            with db:yield db

    def signature(self,song):
        physical=self.catalog.physical(song)
        return hashlib.sha256(json.dumps([MODEL,song['id'],[str(x) for x in physical] if physical else None]).encode()).hexdigest() if physical else None

    def enabled(self):
        return self.token_path.is_file() and not (self.folder/'neural.pause').exists()

    def authorize(self,headers):
        if not self.enabled():return False
        fd=os.open(self.token_path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0))
        try:
            info=os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or stat.S_IMODE(info.st_mode)!=0o600 or info.st_uid!=os.getuid():return False
            token=os.read(fd,129).strip()
        finally:os.close(fd)
        values=headers.get_all('Authorization',[]) if hasattr(headers,'get_all') else [headers.get('Authorization','')]
        return len(token)==64 and len(values)==1 and hmac.compare_digest(values[0].encode(),b'Bearer '+token)

    def available(self,songs):
        # Disabled or malformed cache falls back to the existing acoustic/metadata ranker.
        if not self.enabled():return {}
        try:
            # The recommendation path reads a bounded candidate set, never the full vector catalogue.
            keys=list(songs)[:384]
            if not keys:return {}
            with self.db() as db:rows=list(db.execute('SELECT id,signature,value FROM neural WHERE model=? AND value IS NOT NULL AND id IN ('+','.join('?' for _ in keys)+')',(MODEL,*keys)))
            result={}
            for key,signature,value in rows:
                if key in songs and signature==self.signature(songs[key]):
                    vector=unit([v/255 for v in base64.b64decode(value,validate=True)])
                    if vector:result[key]=vector
            return result
        except (OSError,ValueError,sqlite3.Error):return {}

    def prioritize(self,keys):
        with self.lock:self.priority=list(dict.fromkeys([*keys,*self.priority]))[:300]

    def next(self,extractor=None):
        """One short-lived lease; the worker never receives paths, tags or account history."""
        with self.lease_lock:
            now=time.time()
            if not self.enabled() or (self.lease and self.lease['expires']>now) or now-self.last_lease<30:return None
            songs=self.catalog.songs()
            with self.db() as db:known={r[0]:(r[1],r[2],r[3],r[4]) for r in db.execute('SELECT id,signature,model,updated,value FROM neural')}
            with self.lock:priority=list(self.priority)
            for key in dict.fromkeys([*priority,*songs]):
                song=songs.get(key)
                if song is None:continue
                signature=self.signature(song)
                if not signature:continue
                row=known.get(key)
                if row and row[0]==signature and row[1]==MODEL and row[3] is not None:continue
                if row and now-row[2]<300:continue
                if self.slot is not None and not self.slot.acquire(blocking=False):return None
                self.last_lease=now
                try:
                    raw=(extractor or self.extract)(song)
                    if not isinstance(raw,bytes) or not 15600*2<=len(raw)<=SAMPLES*2 or len(raw)%2:raise ValueError('PCM size rejected')
                except (OSError,ValueError,subprocess.SubprocessError):
                    with self.db() as db:db.execute('INSERT OR REPLACE INTO neural VALUES(?,?,?,?,?)',(key,signature,MODEL,None,time.time()))
                    return None
                finally:
                    if self.slot is not None:self.slot.release()
                if signature!=self.signature(song):return None
                self.lease={'id':key,'signature':signature,'lease':secrets.token_hex(24),'expires':now+180}
                return {'lease':self.lease['lease'],'model':MODEL,'sampleRate':16000,'pcm':base64.b64encode(raw).decode()}
            return None

    def extract(self,song):
        physical=self.catalog.physical(song)
        if physical is None:raise ValueError('Missing source')
        with tempfile.TemporaryDirectory(prefix='neural-',dir=self.folder) as folder:
            output=Path(folder)/'audio.pcm'
            command=[sys.executable,str(Path(__file__).resolve()),'--decode',str(physical[0]),str(output),str(max(0,min(float(song['duration'])*.35,float(song['duration'])-4)))]
            subprocess.run(command,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=12)
            if not output.is_file() or not 15600*2<=output.stat().st_size<=SAMPLES*2:raise ValueError('PCM size rejected')
            return output.read_bytes()

    def complete(self,data):
        if not isinstance(data,dict) or set(data)!={'lease','model','scores'} or data['model']!=MODEL:raise ValueError('Invalid result')
        if data['scores'] is not None and unit(data['scores']) is None:raise ValueError('Empty result')
        with self.lease_lock:
            current=self.lease
            if not self.enabled() or not current or data['lease']!=current['lease'] or time.time()>current['expires']:raise ValueError('Expired lease')
            song=self.catalog.songs().get(current['id'])
            self.lease=None
            if song is None or self.signature(song)!=current['signature']:raise ValueError('Changed source')
            encoded=base64.b64encode(bytes(round(v*255) for v in unit(data['scores']))).decode() if data['scores'] is not None else None
            with self.db() as db:db.execute('INSERT OR REPLACE INTO neural VALUES(?,?,?,?,?)',(current['id'],current['signature'],MODEL,encoded,time.time()))
            return {'ok':True}

    def dispatch(self,handler,chunks):
        import profile_api as profile
        status=400;value={'error':'worker_request_rejected'}
        try:
            if not self.authorize(handler.headers):status=401
            elif handler.command!='POST' or '?' in handler.path:status=405
            else:
                body=bytearray()
                for part in chunks(handler.rfile,handler.headers):
                    body.extend(part)
                    if len(body)>32*1024:raise ValueError('Body limit')
                data=json.loads(body)
                if handler.path==WORKER_ROUTE+'lease' and data=={}:value={'job':self.next()};status=200
                elif handler.path==WORKER_ROUTE+'result':value=self.complete(data);status=200
                elif handler.path==WORKER_ROUTE+'status' and data=={}:
                    with self.db() as db:rows=list(db.execute('SELECT value IS NOT NULL,COUNT(*) FROM neural WHERE model=? GROUP BY value IS NOT NULL',(MODEL,)))
                    counts=dict(rows);value={'analyzed':counts.get(1,0),'failed':counts.get(0,0),'enabled':self.enabled(),'model':MODEL};status=200
        except (OSError,ValueError,TypeError,sqlite3.Error,subprocess.SubprocessError):pass
        profile.send(handler,status,value);return status


if __name__=='__main__':
    # Only the spawned decoder is constrained; never change affinity or limits of the gateway.
    import resource
    import ctypes
    import signal
    parent=os.getppid()
    if len(sys.argv)!=5 or sys.argv[1]!='--decode':raise SystemExit(2)
    if ctypes.CDLL(None,use_errno=True).prctl(1,signal.SIGTERM,0,0,0)!=0 or os.getppid()!=parent:raise SystemExit(1)
    os.nice(19);os.sched_setaffinity(0,{max(os.sched_getaffinity(0))})
    for kind,limit in ((resource.RLIMIT_AS,512*2**20),(resource.RLIMIT_CPU,5),(resource.RLIMIT_FSIZE,SAMPLES*2),(resource.RLIMIT_NOFILE,32),(resource.RLIMIT_CORE,0)):
        resource.setrlimit(kind,(limit,limit))
    os.execvp('ffmpeg',['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-threads','1','-filter_threads','1','-protocol_whitelist','file,pipe',
        '-format_whitelist','mp3,mov,flac,ogg,wav,aac,matroska,webm','-ss',sys.argv[4],'-i',sys.argv[2],'-t',str(SAMPLES/16000),'-map','0:a:0','-vn','-ac','1','-ar','16000','-f','s16le',sys.argv[3]])
