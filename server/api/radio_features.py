# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded20s FFmpeg features and a low-duty single background indexer; no NumPy."""
import argparse
import array
import contextlib
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time

ALGORITHM='ffmpeg6-mono11025-20s-v1'
SAMPLE_RATE=11025
MAX_PCM=20*SAMPLE_RATE*2+65536
MAX_META=2*2**20


def signature(song,physical):
    path,size,mtime=physical
    return hashlib.sha256((ALGORITHM+'|'+str(path)+'|'+str(size)+'|'+str(mtime)).encode()).hexdigest()


def pcm_features(raw):
    values=array.array('h');values.frombytes(raw[:len(raw)//2*2])
    if sys.byteorder!='little':values.byteswap()
    if len(values)<SAMPLE_RATE:raise ValueError('Sample too short')
    square=sum(value*value for value in values)/len(values)
    rms=10*math.log10(max(square/32768**2,1e-12))
    zcr=sum((values[i]>=0)!=(values[i-1]>=0) for i in range(1,len(values)))/(len(values)-1)
    #100Hz amplitude envelope, onset autocorrelation: useful tempo only with confidence.
    window=110;envelope=[math.sqrt(sum(v*v for v in values[i:i+window])/window) for i in range(0,len(values)-window,window)]
    onsets=[max(0,envelope[i]-envelope[i-1]) for i in range(1,len(envelope))]
    mean=statistics.fmean(onsets);centered=[v-mean for v in onsets];variance=sum(v*v for v in centered)
    correlations=[]
    if variance>1:
        for lag in range(30,101):
            score=sum(centered[i]*centered[i-lag] for i in range(lag,len(centered)))/variance
            correlations.append((score,lag))
    score,lag=max(correlations,default=(0,60))
    confidence=max(0,min(1,score));bpm=60*(SAMPLE_RATE/window)/lag if confidence>=.18 else 0
    return {'rms':rms,'zcr':zcr,'tempo':bpm,'tempoConfidence':confidence}


def analyze(path,duration,temporary,runner=subprocess.run):
    with tempfile.TemporaryDirectory(prefix='radio-',dir=temporary) as folder:
        root=Path(folder);pcm=root/'audio.pcm';meta=root/'spectral.txt'
        # Numeric filter metadata only; source tags/filenames never reach logs.
        seek=max(0,min(float(duration)*.35,float(duration)-20))
        command=[sys.executable,str(Path(__file__).resolve()),'--decode',str(path),'--seek',str(seek)]
        result=runner(command,cwd=root,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
        if result.returncode or not pcm.is_file() or not meta.is_file() or pcm.stat().st_size>MAX_PCM or meta.stat().st_size>MAX_META:
            raise ValueError('Audio feature extraction rejected')
        features=pcm_features(pcm.read_bytes());centroids=[];fluxes=[]
        for line in meta.read_text(errors='replace').splitlines():
            key,separator,value=line.partition('=')
            if not separator:continue
            if key not in ('lavfi.aspectralstats.1.centroid','lavfi.aspectralstats.1.flux'):continue
            try:number=float(value)
            except ValueError:continue
            if math.isfinite(number) and number>=0:(centroids if key.endswith('.centroid') else fluxes).append(number)
        if not centroids or not fluxes:raise ValueError('Required acoustic metrics absent')
        features.update(centroid=statistics.median(centroids),flux=statistics.median(fluxes))
        return features


def decode(path,seek):
    import resource
    import ctypes
    import signal
    parent=os.getppid()
    if ctypes.CDLL(None,use_errno=True).prctl(1,signal.SIGTERM,0,0,0)!=0 or os.getppid()!=parent:raise SystemExit(1)
    os.nice(19);os.sched_setaffinity(0,{max(os.sched_getaffinity(0))})
    for key,value in [(resource.RLIMIT_CPU,5),(resource.RLIMIT_AS,1024*2**20),(resource.RLIMIT_DATA,128*2**20),
                      (resource.RLIMIT_FSIZE,MAX_META),(resource.RLIMIT_CORE,0),(resource.RLIMIT_NOFILE,32)]:resource.setrlimit(key,(value,value))
    os.execvp('ffmpeg',['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-threads','1','-filter_threads','1','-max_alloc','33554432',
        '-protocol_whitelist','file,pipe','-ss',str(seek),'-i',str(path),'-t','20','-map','0:a:0','-vn','-ac','1',
        '-af','aformat=channel_layouts=mono,aresample=11025,ametadata=mode=delete,astats=metadata=1:reset=0:measure_perchannel=RMS_level+Zero_crossings_rate:measure_overall=none,aspectralstats=win_size=1024:overlap=0.5:measure=centroid+flux,ametadata=mode=print:file=spectral.txt',
        '-ar',str(SAMPLE_RATE),'-f','s16le','audio.pcm'])


class Index:
    def __init__(self,folder,catalog,slot=None,start=True):
        self.folder=Path(folder);self.folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        if self.folder.is_symlink() or self.folder.resolve()!=self.folder:raise ValueError('Physical radio state required')
        self.path=self.folder/'features.sqlite';self.catalog=catalog;self.slot=slot
        if self.path.is_symlink():raise ValueError('Physical feature database required')
        self.lock=threading.RLock();self.stop=threading.Event();self.priority=[];self.thread=None;self.lockfile=None
        self.next_validation={};self.catalog_rows={};self.catalog_at=0
        with self.db() as db:db.execute('CREATE TABLE IF NOT EXISTS features(id TEXT PRIMARY KEY,signature TEXT NOT NULL,value TEXT,error INTEGER NOT NULL,updated REAL NOT NULL)')
        self.path.chmod(0o600)
        if start:
            import fcntl
            lockpath=self.folder/'analysis.lock'
            if lockpath.is_symlink():raise ValueError('Physical analysis lock required')
            self.lockfile=lockpath.open('a');lockpath.chmod(0o600)
            try:fcntl.flock(self.lockfile,fcntl.LOCK_EX|fcntl.LOCK_NB)
            except BlockingIOError:self.lockfile.close();self.lockfile=None
            if self.lockfile is not None:self.thread=threading.Thread(target=self.loop,name='radio-index',daemon=True);self.thread.start()

    @contextlib.contextmanager
    def db(self):
        with self.lock,contextlib.closing(sqlite3.connect(self.path,timeout=.5)) as db:
            with db:yield db

    def available(self,songs):
        with self.db() as db:rows={row[0]:row[1:] for row in db.execute('SELECT id,signature,value,error,updated FROM features')}
        result={}
        for key,song in songs.items():
            row=rows.get(key)
            if row is None:continue
            physical=self.catalog.physical(song)
            if physical and row and row[0]==signature(song,physical) and not row[2]:result[key]=json.loads(row[1])
        return result

    def prioritize(self,ids):
        with self.lock:self.priority=list(dict.fromkeys([*ids,*self.priority]))[:300]

    def scan_one(self,songs,extractor=analyze,background=False):
        if self.stop.is_set():return False
        with self.db() as db:known={r[0]:r[1:] for r in db.execute('SELECT id,signature,error,updated FROM features')}
        with self.lock:priority=list(self.priority)
        order=list(dict.fromkeys([*priority,*songs]))
        for key in order:
            song=songs.get(key)
            if song is None:continue
            row=known.get(key)
            if background and row and not row[1] and key not in priority and self.next_validation.get(key,0)>time.monotonic():continue
            physical=self.catalog.physical(song)
            if not physical:continue
            stamp=signature(song,physical)
            if row and row[0]==stamp and (not row[1] or time.time()-row[2]<86400):
                self.next_validation[key]=time.monotonic()+300;continue
            if self.slot is not None and not self.slot.acquire(blocking=False):return False
            value=None;error=0
            try:
                value=extractor(physical[0],song['duration'],self.folder)
                after=self.catalog.physical(song)
                if not after or signature(song,after)!=stamp:return False
            except (OSError,ValueError,subprocess.SubprocessError):error=1
            finally:
                if self.slot is not None:self.slot.release()
            with self.db() as db:db.execute('INSERT OR REPLACE INTO features VALUES(?,?,?,?,?)',(key,stamp,json.dumps(value) if value is not None else None,error,time.time()))
            return True
        return False

    def loop(self):
        while not self.stop.is_set():
            started=time.monotonic()
            try:
                busy=os.getloadavg()[0]>.8 or (self.folder/'analysis.pause').exists() or shutil.disk_usage(self.folder).free<10*2**30+4*2**20
                with self.lock:missing=any(key not in self.catalog_rows for key in self.priority)
                if not busy and (time.monotonic()-self.catalog_at>300 or missing):
                    self.catalog_rows=self.catalog.songs();self.catalog_at=time.monotonic()
                done=False if busy else self.scan_one(self.catalog_rows,background=True)
            except (OSError,ValueError,sqlite3.Error):done=False
            # At most one extraction; <=20% elapsed duty and minimum5s pause.
            self.stop.wait(max(5,(time.monotonic()-started)*4) if done else 30)

    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=16)
        if self.lockfile is not None and (self.thread is None or not self.thread.is_alive()):self.lockfile.close();self.lockfile=None


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--decode',type=Path,required=True);parser.add_argument('--seek',type=float,required=True)
    args=parser.parse_args();decode(args.decode,args.seek)
