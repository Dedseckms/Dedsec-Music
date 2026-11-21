# SPDX-License-Identifier: GPL-3.0-or-later
"""Opt-in, single-lease forced alignment of real cached plain lyrics."""
import contextlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import secrets
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time

import profile_api as profile

ROUTE='/dedsec/profile/v1/lyrics-align/worker/'
MODEL='mfa-ru-gmm-v1'
MAX_PCM=600*16000*2
TTL=7200
MAX_RESULT_BYTES=512*2**20
MAX_JOBS=100000


class Broker:
    def __init__(self,manager,slot=None):
        self.manager=manager;self.slot=slot;self.lock=threading.Lock()
        self.folder=manager.folder/'alignment';self.folder.mkdir(mode=0o700,exist_ok=True)
        if self.folder.is_symlink() or self.folder.resolve()!=self.folder:raise ValueError('Physical alignment folder required')
        self.token_path=self.folder/'worker.token'
        with manager.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS lyrics_align_control(id INTEGER PRIMARY KEY CHECK(id=1),enabled INTEGER NOT NULL,last_lease REAL NOT NULL,cursor TEXT NOT NULL)''')
            db.execute("INSERT OR IGNORE INTO lyrics_align_control VALUES(1,0,0,'')")
            db.execute('''CREATE TABLE IF NOT EXISTS lyrics_align_job(song TEXT PRIMARY KEY,version TEXT NOT NULL,revision TEXT NOT NULL,
              status TEXT NOT NULL,retry REAL NOT NULL,lease TEXT,expires REAL NOT NULL,result TEXT,attempts INTEGER NOT NULL)''')

    def authorize(self,headers):
        try:
            fd=os.open(self.token_path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0))
            with os.fdopen(fd,'rb') as stream:
                info=os.fstat(stream.fileno());token=stream.read(129).strip()
            if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or stat.S_IMODE(info.st_mode)!=0o600 or info.st_uid!=os.getuid() or not re.fullmatch(b'[a-f0-9]{64}',token):return False
            values=headers.get_all('Authorization',[]) if hasattr(headers,'get_all') else [headers.get('Authorization','')]
            return len(values)==1 and hmac.compare_digest(values[0].encode(),b'Bearer '+token)
        except (OSError,ValueError,UnicodeError):return False

    def enabled(self):
        with self.manager.db() as db:return bool(db.execute('SELECT enabled FROM lyrics_align_control WHERE id=1').fetchone()[0])

    def control(self,owner,enabled):
        self.manager.batch.owner_identity(owner)
        with self.manager.db() as db:db.execute("UPDATE lyrics_align_control SET enabled=?,cursor='' WHERE id=1",(int(enabled),))
        if not enabled:self.cancel_active()

    def status(self):
        with self.manager.db() as db:
            counts=dict(db.execute('SELECT status,count(*) FROM lyrics_align_job GROUP BY status'))
            enabled=bool(db.execute('SELECT enabled FROM lyrics_align_control WHERE id=1').fetchone()[0])
        return {'enabled':enabled,'total':sum(counts.values()),'pending':counts.get('pending',0),
                'done':counts.get('done',0),'skipped':counts.get('skipped',0),'errors':counts.get('error',0),'active':counts.get('leased',0)}

    def discard_audio(self,lease):
        if not isinstance(lease,str) or not re.fullmatch(r'[a-f0-9]{48}',lease):return
        path=self.folder/(lease+'.pcm')
        if path.is_symlink():raise ValueError('Physical alignment audio required')
        with contextlib.suppress(FileNotFoundError):path.unlink()

    def cancel_active(self):
        with self.manager.db() as db:
            rows=list(db.execute("SELECT lease FROM lyrics_align_job WHERE status='leased'"))
            db.execute("UPDATE lyrics_align_job SET status='pending',lease=NULL,expires=0 WHERE status='leased'")
        for row in rows:self.discard_audio(row[0])

    def owner(self):
        return self.manager.batch.owner_identity(self.manager.protected_owner)['id']

    def current(self,key,version,revision):
        owner=self.owner();song,current=self.manager.song(owner,key)
        if current!=version:raise profile.ProfileError(409)
        with self.manager.db() as db:
            row=db.execute('SELECT value FROM cache WHERE key=?',(version,)).fetchone()
            manual=db.execute('SELECT 1 FROM timing WHERE song=?',(key,)).fetchone()
        value=json.loads(row[0]) if row else None
        if manual or not value or value.get('revision')!=revision or value.get('status')!='ready' or value.get('synced'):
            raise profile.ProfileError(409)
        return song,value

    def discover(self):
        if not self.enabled():return
        try:self.owner()
        except profile.ProfileError:
            with self.manager.db() as db:db.execute('UPDATE lyrics_align_control SET enabled=0 WHERE id=1')
            self.cancel_active();return
        with self.manager.db() as db:
            cursor=db.execute('SELECT cursor FROM lyrics_align_control WHERE id=1').fetchone()[0]
            rows=list(db.execute('''SELECT b.song,b.version,c.value FROM lyrics_batch_song b JOIN cache c ON c.key=b.version
              WHERE b.song>? ORDER BY b.song LIMIT 128''',(cursor,)))
        for key,version,raw in rows:
            value=json.loads(raw)
            if value.get('status')!='ready' or value.get('synced') or not value.get('revision'):continue
            try:song,value=self.current(key,version,value['revision'])
            except profile.ProfileError:continue
            status='pending' if 0<song['duration']<=600 and re.search('[А-Яа-яЁё]',' '.join(x['text'] for x in value['lines'])) else 'skipped'
            with self.manager.db() as db:
                old=db.execute('SELECT version,revision,status FROM lyrics_align_job WHERE song=?',(key,)).fetchone()
                if not old and db.execute('SELECT count(*) FROM lyrics_align_job').fetchone()[0]>=MAX_JOBS:
                    db.execute('UPDATE lyrics_align_control SET enabled=0 WHERE id=1');return
                if old and old[:2]==(version,value['revision']):continue
                # Never replace an active lease; its source checks will reject a changed revision.
                if old and old[2]=='leased':continue
                db.execute('INSERT OR REPLACE INTO lyrics_align_job VALUES(?,?,?,?,0,NULL,0,NULL,0)',(key,version,value['revision'],status))
        with self.manager.db() as db:db.execute('UPDATE lyrics_align_control SET cursor=? WHERE id=1',(rows[-1][0] if rows else '',))

    def recover(self):
        now=time.time()
        with self.manager.db() as db:
            rows=list(db.execute("SELECT lease FROM lyrics_align_job WHERE status='leased' AND expires<=?",(now,)))
            db.execute("UPDATE lyrics_align_job SET status='error',retry=?,lease=NULL,expires=0 WHERE status='leased' AND expires<=?",(now+300,now))
        for row in rows:self.discard_audio(row[0])
        with self.manager.db() as db:active={r[0] for r in db.execute("SELECT lease FROM lyrics_align_job WHERE status='leased'")}
        # This private directory reserves opaque 48-hex PCM names for broker output.
        for path in self.folder.glob('*.pcm'):
            if not re.fullmatch(r'[a-f0-9]{48}\.pcm',path.name) or path.stem in active:continue
            info=path.lstat()
            if stat.S_ISREG(info.st_mode) and info.st_nlink==1 and info.st_uid==os.getuid():self.discard_audio(path.stem)

    def next(self,extractor=None):
        with self.lock:
            self.recover()
            if not self.enabled():return None
            self.discover();now=time.time()
            with self.manager.db() as db:
                db.execute('BEGIN IMMEDIATE')
                if db.execute("SELECT 1 FROM lyrics_align_job WHERE status='leased'").fetchone():return None
                last=db.execute('SELECT last_lease FROM lyrics_align_control WHERE id=1').fetchone()[0]
                if now-last<30:return None
                row=db.execute("SELECT song,version,revision FROM lyrics_align_job WHERE attempts<3 AND (status='pending' OR (status='error' AND retry<=?)) ORDER BY song LIMIT 1",(now,)).fetchone()
                if not row:return None
                key,version,revision=row;lease=secrets.token_hex(24);expires=int(now)+TTL
                db.execute("UPDATE lyrics_align_job SET status='leased',lease=?,expires=?,attempts=attempts+1 WHERE song=?",(lease,expires,key))
                db.execute('UPDATE lyrics_align_control SET last_lease=? WHERE id=1',(now,))
            acquired=False
            try:
                song,value=self.current(key,version,revision)
                if self.slot is not None:
                    acquired=self.slot.acquire(blocking=False)
                    if not acquired:
                        with self.manager.db() as db:db.execute("UPDATE lyrics_align_job SET status='pending',lease=NULL,expires=0,attempts=attempts-1 WHERE lease=?",(lease,))
                        return None
                raw=(extractor or self.extract)(song)
                if not isinstance(raw,bytes) or not 32000<=len(raw)<=MAX_PCM or len(raw)%2 or abs(len(raw)/32000-song['duration'])>2:raise ValueError('PCM bounds')
                self.current(key,version,revision)
                self.lease(lease)
                path=self.folder/(lease+'.pcm')
                with path.open('xb') as output:os.chmod(path,0o600);output.write(raw)
                self.lease(lease)
                return {'lease':lease,'model':MODEL,'language':'ru','sampleRate':16000,'samples':len(raw)//2,
                        'durationMs':round(song['duration']*1000),'lines':[line['text'] for line in value['lines'] if line['text'].strip()],
                        'expiresAt':expires,'revision':revision}
            except (OSError,ValueError,profile.ProfileError,subprocess.SubprocessError):
                with self.manager.db() as db:db.execute("UPDATE lyrics_align_job SET status='error',retry=?,lease=NULL,expires=0 WHERE lease=?",(time.time()+300,lease))
                self.discard_audio(lease);return None
            finally:
                if acquired:self.slot.release()

    def lease(self,lease):
        if not isinstance(lease,str) or not re.fullmatch(r'[a-f0-9]{48}',lease) or not self.enabled():raise profile.ProfileError(409)
        with self.manager.db() as db:row=db.execute("SELECT song,version,revision,expires FROM lyrics_align_job WHERE lease=? AND status='leased'",(lease,)).fetchone()
        if not row or row[3]<=time.time():raise profile.ProfileError(409)
        try:song,value=self.current(*row[:3])
        except profile.ProfileError as error:
            with self.manager.db() as db:
                db.execute("UPDATE lyrics_align_job SET status='skipped',lease=NULL,expires=0 WHERE lease=?",(lease,))
                if error.status in (401,403):db.execute('UPDATE lyrics_align_control SET enabled=0 WHERE id=1')
            self.discard_audio(lease);raise
        return row,song,value

    def audio(self,lease):
        self.lease(lease);path=self.folder/(lease+'.pcm')
        fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0))
        stream=os.fdopen(fd,'rb');info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or stat.S_IMODE(info.st_mode)!=0o600 or not 32000<=info.st_size<=MAX_PCM:
            stream.close();raise ValueError('PCM bounds')
        return stream,info.st_size

    def complete(self,data):
        from lyrics_api import response
        if not isinstance(data,dict) or data.get('model')!=MODEL:raise ValueError('Model mismatch')
        status=data.get('status');expected={'lease','model','status','startsMs','coverage'} if status=='aligned' else {'lease','model','status','reason'}
        if set(data)!=expected or status not in ('aligned','unsupported','error'):raise ValueError('Result shape')
        with self.lock:
            row,song,value=self.lease(data['lease']);key,version,revision,expires=row
            result=None
            if status=='aligned':
                source_lines=[line for line in value['lines'] if line['text'].strip()]
                starts=data['startsMs'];coverage=data['coverage'];previous=-1
                if type(coverage) not in (int,float) or not math.isfinite(coverage) or coverage!=1 or not isinstance(starts,list) or len(starts)!=len(source_lines):raise ValueError('Incomplete coverage')
                lines=[]
                for line,start in zip(source_lines,starts):
                    if not line['text'].strip():
                        if start is not None:raise ValueError('Blank line timestamp')
                    else:
                        if type(start) is not int or not previous<=start<=song['duration']*1000 or start<0:raise ValueError('Timestamp bounds')
                        previous=start
                    lines.append({'startMs':start,'text':line['text']})
                if previous<0:raise ValueError('Empty alignment')
                result=response('ready',lines,True,'Automatic alignment (MFA)')
            elif data['reason'] not in (('OOV','LANGUAGE','LIMIT','QUALITY') if status=='unsupported' else ('ALIGNMENT_FAILED',)):raise ValueError('Reason rejected')
            self.current(key,version,revision)
            if result:
                raw=json.dumps(result,separators=(',',':'))
                with self.manager.db() as db:used=db.execute('SELECT coalesce(sum(length(result)),0) FROM lyrics_align_job').fetchone()[0]
                if used+len(raw.encode())>MAX_RESULT_BYTES:
                    with self.manager.db() as db:db.execute('UPDATE lyrics_align_control SET enabled=0 WHERE id=1')
                    self.cancel_active();raise profile.ProfileError(507)
            with self.manager.db() as db:
                db.execute('BEGIN IMMEDIATE')
                if not db.execute('SELECT enabled FROM lyrics_align_control WHERE id=1').fetchone()[0]:raise profile.ProfileError(409)
                active=db.execute("SELECT song,version,revision,expires FROM lyrics_align_job WHERE lease=? AND status='leased'",(data['lease'],)).fetchone()
                if active!=row or active[3]<=time.time():raise profile.ProfileError(409)
                self.owner()
                if self.manager.song(self.manager.protected_owner,key)[1]!=version:raise profile.ProfileError(409)
                current=db.execute('SELECT value FROM cache WHERE key=?',(version,)).fetchone()
                if not current or json.loads(current[0]).get('revision')!=revision or db.execute('SELECT 1 FROM timing WHERE song=?',(key,)).fetchone():raise profile.ProfileError(409)
                if result:
                    stamp=time.time()
                    db.execute('INSERT INTO cache VALUES(?,?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,expires=excluded.expires,updated=excluded.updated',(version,raw,stamp+365*86400,stamp))
                    db.execute("UPDATE lyrics_batch_song SET status='synced' WHERE song=? AND version=?",(key,version))
                else:raw=None
                db.execute('UPDATE lyrics_align_job SET status=?,retry=?,lease=NULL,expires=0,result=? WHERE lease=?',
                           ('done' if result else 'skipped' if status=='unsupported' else 'error',time.time()+3600,raw,data['lease']))
                self.manager.prune(db)
            self.discard_audio(data['lease']);return {'ok':True}

    def restore(self,version,incoming=None):
        with self.manager.db() as db:
            row=db.execute("SELECT revision,result FROM lyrics_align_job WHERE version=? AND status='done' AND result IS NOT NULL",(version,)).fetchone()
        if not row or incoming is not None and (incoming.get('synced') or incoming.get('revision')!=row[0]):return incoming
        return json.loads(row[1])

    def extract(self,song):
        physical=self.manager.catalog.physical(song)
        if physical is None or not 0<song['duration']<=600:raise ValueError('Duration bounds')
        with tempfile.TemporaryDirectory(prefix='decode-',dir=self.folder) as folder:
            path=Path(folder)/'audio.pcm'
            subprocess.run([sys.executable,str(Path(__file__).resolve()),'--decode',str(physical[0]),str(path)],stdin=subprocess.DEVNULL,
                           stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=True,timeout=30)
            if not path.is_file() or not 32000<=path.stat().st_size<=MAX_PCM:raise ValueError('PCM bounds')
            return path.read_bytes()

    def dispatch(self,handler,chunks):
        status=400;value={'error':'alignment_worker_rejected'}
        try:
            if not self.authorize(handler.headers):raise profile.ProfileError(401)
            if handler.command!='POST' or '?' in handler.path:raise profile.ProfileError(405)
            raw=bytearray()
            for part in chunks(handler.rfile,handler.headers):
                raw.extend(part)
                if len(raw)>32768:raise ValueError('Body bounds')
            data=json.loads(raw)
            if handler.path==ROUTE+'lease' and data=={}:value={'job':self.next()}
            elif handler.path==ROUTE+'result':value=self.complete(data)
            elif handler.path==ROUTE+'status' and data=={}:self.recover();value=self.status()
            elif handler.path==ROUTE+'audio' and isinstance(data,dict) and set(data)=={'lease'}:
                stream,size=self.audio(data['lease'])
                with stream:
                    handler.send_response(200);handler.send_header('Content-Type','application/octet-stream');handler.send_header('Content-Length',str(size));handler.send_header('Cache-Control','no-store');handler.end_headers()
                    while block:=stream.read(65536):handler.wfile.write(block)
                return 200
            else:raise ValueError('Route rejected')
            status=200
        except profile.ProfileError as error:status=error.status
        except (OSError,ValueError,TypeError,KeyError,sqlite3.Error,subprocess.SubprocessError):pass
        profile.send(handler,status,value);return status


if __name__=='__main__':
    import ctypes
    import resource
    import signal
    parent=os.getppid()
    if len(sys.argv)!=4 or sys.argv[1]!='--decode':raise SystemExit(2)
    if ctypes.CDLL(None,use_errno=True).prctl(1,signal.SIGTERM,0,0,0)!=0 or os.getppid()!=parent:raise SystemExit(1)
    os.nice(19);os.sched_setaffinity(0,{max(os.sched_getaffinity(0))})
    for kind,limit in ((resource.RLIMIT_AS,512*2**20),(resource.RLIMIT_CPU,20),(resource.RLIMIT_FSIZE,MAX_PCM),(resource.RLIMIT_NOFILE,32),(resource.RLIMIT_CORE,0)):
        resource.setrlimit(kind,(limit,limit))
    os.execvp('ffmpeg',['ffmpeg','-nostdin','-hide_banner','-loglevel','error','-threads','1','-filter_threads','1',
        '-protocol_whitelist','file,pipe','-format_whitelist','mp3,mov,flac,ogg,wav,aac,matroska,webm',
        '-i',sys.argv[2],'-t','600','-map','0:a:0','-vn','-ac','1','-ar','16000','-f','s16le',sys.argv[3]])
