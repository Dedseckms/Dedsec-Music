# SPDX-License-Identifier: GPL-3.0-or-later
"""Durable per-account jobs; credentials live only in the single worker's memory."""
import collections
import contextlib
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import threading
import time
import uuid

import audio
import profile_api as profile
from import_errors import Cancelled, ImportFailure, check_cancel
import yandex_source as source
import import_playlists

PREFIX='/dedsec/import/v1/yandex/jobs'
ACTIVE=('queued','running','cancelling','paused_auth')
TERMINAL=('cancelled','completed','finished_with_errors','failed')
MAX_QUEUE=8
PUBLIC_ERRORS={'AUTH_REQUIRED','SOURCE_UNAVAILABLE','NETWORK_ERROR','NO_SPACE','SOURCE_LIMIT','INTERNAL_ERROR','CANCELLED'}

class ApiError(Exception):
    def __init__(self,status,code):self.status=status;self.code=code

def now():return datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')

def digest_file(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def atomic_link(temporary,destination):
    descriptor=audio.safe_directory(destination.parent)
    try:
        os.link(temporary,destination.name,dst_dir_fd=descriptor,follow_symlinks=False)
        os.fsync(descriptor)
    finally:os.close(descriptor)

def error_code(error):
    if isinstance(error,ImportFailure):return error.code if error.code in PUBLIC_ERRORS else 'INTERNAL_ERROR'
    if isinstance(error,profile.ProfileError):return 'AUTH_REQUIRED' if error.status==401 else 'NETWORK_ERROR'
    if type(error).__name__ in ('UnauthorizedError','PermissionError'):return 'AUTH_REQUIRED'
    if type(error).__name__ in ('NetworkError','TimedOutError','TimeoutError','ConnectionError','URLError'):return 'NETWORK_ERROR'
    return 'INTERNAL_ERROR'

def cleanup_staging(staging,started,uid,metadata=lambda path:path.lstat()):
    """Startup only, after exclusive worker lock; preserve unknown or active files."""
    for path in staging.iterdir():
        if not re.fullmatch(r'(?:import-[0-9a-f]{32}|audio-[a-z0-9_]{8})\.pending',path.name):continue
        info=metadata(path)
        if (stat.S_ISREG(info.st_mode) and stat.S_IMODE(info.st_mode)==0o600
                and info.st_uid==uid and info.st_mtime<started):path.unlink()

class Manager:
    def __init__(self,state_root,storage,provider_factory=source.Provider,start_worker=True,publisher=atomic_link,playlist_client=import_playlists.Native):
        started=time.time()
        if Path(state_root).is_symlink() or Path(storage).is_symlink():raise RuntimeError('Physical roots required')
        self.root=Path(state_root).resolve()/'imports';self.storage=Path(storage).resolve()
        self.library=self.storage/'library';self.staging=self.storage/'staging'
        for folder in (self.root,self.library,self.staging):
            folder.mkdir(mode=0o700,parents=True,exist_ok=True)
            if folder.is_symlink() or folder.resolve()!=folder:raise RuntimeError('Importer directories must be physical')
        self.path=self.root/'jobs.sqlite';self.lock=threading.RLock();self.wake=threading.Event();self.stop=threading.Event()
        if self.path.is_symlink():raise RuntimeError('Importer database must be a regular private file')
        self.credentials={};self.events={};self.provider_factory=provider_factory;self.worker=None;self.worker_lock=None
        self.publisher=publisher
        self.playlist_client=playlist_client
        if start_worker:
            import fcntl
            self.worker_lock=(self.root/'worker.lock').open('a')
            fcntl.flock(self.worker_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
            if (self.staging.stat().st_uid!=os.getuid() or stat.S_IMODE(self.staging.stat().st_mode)!=0o700):
                raise RuntimeError('Staging must be private0700 owned by this process user')
            cleanup_staging(self.staging,started,os.getuid())
        with self.db() as db:
            db.executescript('''
              PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,uid TEXT NOT NULL,state TEXT NOT NULL,phase TEXT NOT NULL,
                created TEXT NOT NULL,updated TEXT NOT NULL,finished TEXT,error TEXT,current TEXT,account_hash TEXT,inventory_complete INTEGER NOT NULL DEFAULT 0,
                audio_complete INTEGER NOT NULL DEFAULT 0,next_attempt REAL NOT NULL DEFAULT 0,index_attempts INTEGER NOT NULL DEFAULT 0);
              CREATE INDEX IF NOT EXISTS jobs_user ON jobs(uid,created);
              CREATE TABLE IF NOT EXISTS items(job TEXT NOT NULL,id TEXT NOT NULL,kind TEXT NOT NULL DEFAULT 'pending',
                meta TEXT NOT NULL DEFAULT '{}',outcome TEXT,bytes INTEGER NOT NULL DEFAULT 0,error TEXT,PRIMARY KEY(job,id));
              CREATE TABLE IF NOT EXISTS downloads(id TEXT PRIMARY KEY,path TEXT NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,seconds REAL NOT NULL,origin TEXT NOT NULL DEFAULT 'published');
              CREATE TABLE IF NOT EXISTS playlists(job TEXT NOT NULL,source_key TEXT NOT NULL,name TEXT NOT NULL,ids TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'pending',missing INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(job,source_key));
              CREATE TABLE IF NOT EXISTS bindings(uid TEXT NOT NULL,source_key TEXT NOT NULL,comment TEXT NOT NULL,name TEXT NOT NULL,
                snapshot TEXT,intent TEXT,creating INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(uid,source_key));
            ''')
            db.execute("UPDATE jobs SET state='paused_auth',phase='paused',error='AUTH_REQUIRED',current=NULL,updated=? WHERE state IN ('queued','running')",(now(),))
            db.execute("UPDATE jobs SET state='cancelled',phase='finished',error='CANCELLED',finished=?,updated=?,current=NULL WHERE state='cancelling'",(now(),now()))
        self.path.chmod(0o600)
        if start_worker:
            self.worker=threading.Thread(target=self._loop,name='music-import',daemon=True);self.worker.start()

    @contextlib.contextmanager
    def db(self):
        with self.lock:
            db=sqlite3.connect(self.path,timeout=2);db.row_factory=sqlite3.Row
            try:
                with db:yield db
            finally:db.close()

    def update(self,key,**values):
        allowed={'state','phase','finished','error','current','account_hash','inventory_complete','audio_complete','next_attempt','index_attempts'}
        if set(values)-allowed:raise RuntimeError('Unknown job field')
        values['updated']=now()
        with self.db() as db:
            db.execute('UPDATE jobs SET '+','.join(name+'=?' for name in values)+' WHERE id=?',(*values.values(),key))

    def _own(self,db,uid,key):
        row=db.execute('SELECT * FROM jobs WHERE id=? AND uid=?',(key,uid)).fetchone()
        if row is None:raise ApiError(404,'NOT_FOUND')
        return row

    def view(self,uid,key=None):
        with self.db() as db:
            row=self._own(db,uid,key) if key else db.execute('SELECT * FROM jobs WHERE uid=? ORDER BY created DESC LIMIT 1',(uid,)).fetchone()
            if row is None:return None
            classes=collections.Counter({r[0]:r[1] for r in db.execute('SELECT kind,COUNT(*) FROM items WHERE job=? GROUP BY kind',(row['id'],))})
            outcomes=collections.Counter({r[0]:r[1] for r in db.execute("SELECT outcome,COUNT(*) FROM items WHERE job=? AND kind='music' GROUP BY outcome",(row['id'],))})
            total_bytes=db.execute("SELECT COALESCE(SUM(bytes),0) FROM items WHERE job=? AND outcome IN ('downloaded','skipped')",(row['id'],)).fetchone()[0]
            lists=collections.Counter({r[0]:r[1] for r in db.execute('SELECT status,COUNT(*) FROM playlists WHERE job=? GROUP BY status',(row['id'],))})
            missing=db.execute('SELECT COALESCE(SUM(missing),0) FROM playlists WHERE job=?',(row['id'],)).fetchone()[0]
            return {'id':row['id'],'state':row['state'],'phase':row['phase'],'discovered':sum(classes.values()),
                'total':classes['music'],'downloaded':outcomes['downloaded'],'skipped':outcomes['skipped'],
                'failed':outcomes['failed'],'unavailable':outcomes['unavailable'],'excluded':classes['excluded'],
                'processed':sum(outcomes[k] for k in ('downloaded','skipped','failed','unavailable')),
                'current':json.loads(row['current']) if row['current'] else None,'totalBytes':total_bytes,
                'createdAt':row['created'],'updatedAt':row['updated'],'finishedAt':row['finished'],'errorCode':row['error'],
                'playlistCount':sum(lists.values())-lists['excluded'],'completedPlaylistCount':lists['completed'],
                'pendingPlaylistCount':lists['pending'],'missingPlaylistTracks':missing}

    def schedule(self,identity,token,key=None):
        if not isinstance(token,str) or not 8<=len(token)<=8192 or any(ord(c)<33 or ord(c)>126 for c in token):raise ApiError(400,'INVALID_TOKEN')
        source.check_space(self.root,10*2**20)
        uid=identity['id']
        with self.lock,self.db() as db:
            if db.execute("SELECT COUNT(*) FROM jobs WHERE state IN ('queued','running','cancelling')").fetchone()[0]>=MAX_QUEUE:raise ApiError(429,'QUEUE_FULL')
            if key:
                row=self._own(db,uid,key)
                if row['state'] not in ('paused_auth','cancelled','failed','finished_with_errors'):raise ApiError(409,'INVALID_STATE')
                if db.execute("SELECT 1 FROM jobs WHERE uid=? AND id!=? AND state IN ('queued','running','cancelling','paused_auth')",(uid,key)).fetchone():raise ApiError(409,'JOB_EXISTS')
                db.execute("UPDATE items SET outcome=NULL,error=NULL,bytes=0 WHERE job=? AND outcome='failed'",(key,))
                db.execute("UPDATE items SET kind='pending',outcome=NULL,error=NULL,bytes=0 WHERE job=? AND outcome='unavailable'",(key,))
                db.execute("UPDATE jobs SET state='queued',phase='queued',error=NULL,finished=NULL,current=NULL,audio_complete=0,next_attempt=0,index_attempts=0,updated=? WHERE id=?",(now(),key))
            else:
                if db.execute("SELECT 1 FROM jobs WHERE uid=? AND state IN ('queued','running','cancelling','paused_auth')",(uid,)).fetchone():raise ApiError(409,'JOB_EXISTS')
                if db.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]>=200:raise ApiError(422,'SOURCE_LIMIT')
                key=str(uuid.uuid4());stamp=now()
                db.execute('INSERT INTO jobs(id,uid,state,phase,created,updated) VALUES(?,?,?,?,?,?)',(key,uid,'queued','queued',stamp,stamp))
            self.credentials[key]={'oauth':token,'native':identity['token']}
            self.events[key]=threading.Event()
        self.wake.set();return self.view(uid,key)

    def cancel(self,uid,key):
        with self.lock,self.db() as db:
            row=self._own(db,uid,key)
            if row['state'] in TERMINAL:return self.view(uid,key)
            running=row['state'] in ('running','cancelling')
            db.execute('UPDATE jobs SET state=?,phase=?,error=?,finished=?,updated=? WHERE id=?',
                ('cancelling' if running else 'cancelled',row['phase'] if running else 'finished',
                 None if running else 'CANCELLED',None if running else now(),now(),key))
            self.events.setdefault(key,threading.Event()).set()
            if not running:self.credentials.pop(key,None)
        self.wake.set();return self.view(uid,key)

    def _retry(self,operation,event):
        for attempt in range(2):
            check_cancel(event)
            try:return operation()
            except ImportFailure as error:
                if error.code!='NETWORK_ERROR' or attempt:raise
                if event.wait(2):raise Cancelled()

    def _item(self,job,key,**values):
        if set(values)-{'kind','meta','outcome','bytes','error'}:raise RuntimeError('Unknown item field')
        with self.db() as db:
            db.execute('UPDATE items SET '+','.join(name+'=?' for name in values)+' WHERE job=? AND id=?',(*values.values(),job,key))
            db.execute('UPDATE jobs SET updated=? WHERE id=?',(now(),job))

    def cached(self,key):
        with self.db() as db:row=db.execute('SELECT * FROM downloads WHERE id=?',(key,)).fetchone()
        if row is None:return None
        path=self.library/row['path']
        prefixes=('yandex/',) if row['origin']=='published' else ('tracks/',) if row['origin']=='legacy_verified' else ()
        allowed={prefix+key+suffix for prefix in prefixes for suffix in ('.mp3','.m4a','.flac')}
        if (row['path'] not in allowed or path.is_symlink()
                or path.resolve()!=path or not path.is_file() or path.stat().st_size!=row['bytes']):return None
        return dict(row) if digest_file(path)==row['sha'] else None

    def _one(self,job,key,provider,event,credentials):
        cached=self.cached(key)
        if cached:
            with self.db() as db:old=db.execute('SELECT outcome FROM items WHERE job=? AND id=?',(job,key)).fetchone()[0]
            self._item(job,key,outcome=old if old in ('downloaded','skipped') else 'skipped',bytes=cached['bytes'],error=None)
            return
        # One audio transfer/validator at a time, shared with manual uploads.
        while not audio.UPLOAD.acquire(timeout=1):check_cancel(event)
        temporary=self.staging/('import-'+uuid.uuid4().hex+'.pending')
        try:
            check_cancel(event);source.check_space(self.staging,audio.MAX_BYTES)
            def attempt():
                temporary.unlink(missing_ok=True)
                return provider.download(key,temporary)
            suffix,meta=self._retry(attempt,event)
            if meta['kind']!='music':self._item(job,key,kind='excluded',meta=json.dumps(meta),outcome=None,bytes=0,error=None);return
            if suffix not in ('.mp3','.m4a'):raise ImportFailure('SOURCE_UNAVAILABLE')
            try:seconds=audio.validate(temporary)
            except audio.Rejected:raise ImportFailure('SOURCE_UNAVAILABLE') from None
            if abs(seconds-meta['durationMs']/1000)>max(2,meta['durationMs']/1000*0.02):raise ImportFailure('SOURCE_UNAVAILABLE')
            check_cancel(event)
            try:source.tag(temporary,meta,suffix)
            except ImportFailure:raise
            except Exception:raise ImportFailure('SOURCE_UNAVAILABLE') from None
            check_cancel(event)
            size=temporary.stat().st_size
            if size>audio.MAX_BYTES:raise ImportFailure('SOURCE_LIMIT')
            checksum=digest_file(temporary)
            folder=self.library/'yandex';folder.mkdir(mode=0o700,exist_ok=True)
            # Intent is durable before publication, allowing crash recovery by exact hash.
            with self.lock:
                check_cancel(event);source.check_space(self.library)
                current,_=profile.native(credentials['native'],'/api/user/'+credentials['uid'])
                if current.get('id')!=credentials['uid']:raise ImportFailure('AUTH_REQUIRED')
                with self.db() as db:
                    db.execute('INSERT OR REPLACE INTO downloads(id,path,bytes,sha,seconds,origin) VALUES(?,?,?,?,?,?)',(key,'yandex/'+key+suffix,size,checksum,seconds,'published'))
                try:self.publisher(temporary,folder/(key+suffix))
                except FileExistsError:
                    if self.cached(key) is None:raise ImportFailure('SOURCE_UNAVAILABLE') from None
                self._item(job,key,meta=json.dumps(meta),outcome='downloaded',bytes=size,error=None)
        finally:
            temporary.unlink(missing_ok=True)
            audio.UPLOAD.release()

    def run_job(self,key):
        with self.lock:
            credentials=self.credentials.get(key)
            if credentials is None:return
            event=self.events[key]
        provider=None;deferred=False
        try:
            with self.db() as db:row=db.execute('SELECT * FROM jobs WHERE id=?',(key,)).fetchone()
            if row['state']!='queued':return
            credentials=dict(credentials,uid=row['uid'])
            check_cancel(event);self.update(key,state='running',phase='inventory',error=None)
            current,_=profile.native(credentials['native'],'/api/user/'+credentials['uid'])
            if current.get('id')!=credentials['uid']:raise ImportFailure('AUTH_REQUIRED')
            provider=self._retry(lambda:self.provider_factory(credentials['oauth'],event),event)
            if row['account_hash'] and row['account_hash']!=provider.account_hash:raise ImportFailure('AUTH_REQUIRED')
            self.update(key,account_hash=provider.account_hash)
            if not row['inventory_complete']:
                occurrences=0;batch=[]
                def save_batch(values):
                    with self.db() as db:
                        db.executemany('INSERT OR IGNORE INTO items(job,id) VALUES(?,?)',[(key,value) for value in values])
                        if db.execute('SELECT COUNT(*) FROM items WHERE job=?',(key,)).fetchone()[0]>source.MAX_TRACKS:raise ImportFailure('SOURCE_LIMIT')
                        db.execute('UPDATE jobs SET updated=? WHERE id=?',(now(),key))
                for track in provider.inventory():
                    check_cancel(event);source.check_space(self.root,1024*1024);track=source.source_id(track);occurrences+=1
                    if occurrences>500_000:raise ImportFailure('SOURCE_LIMIT')
                    batch.append(track)
                    if len(batch)==100:save_batch(batch);batch=[]
                if batch:save_batch(batch)
                with self.db() as db:
                    for section in getattr(provider,'playlists',[]):
                        if (not isinstance(section,dict) or set(section)!={'key','name','ids'}
                                or not isinstance(section['key'],str) or len(section['key'])>160
                                or not isinstance(section['name'],str) or len(section['name'])>320
                                or not isinstance(section['ids'],list) or len(section['ids'])>source.MAX_TRACKS):raise ImportFailure('SOURCE_LIMIT')
                        ids=[source.source_id(value) for value in section['ids']]
                        db.execute('INSERT OR REPLACE INTO playlists(job,source_key,name,ids) VALUES(?,?,?,?)',
                            (key,provider.account_hash+':'+section['key'],section['name'],json.dumps(ids)))
                    db.execute('UPDATE jobs SET inventory_complete=1 WHERE id=?',(key,))
            if not row['audio_complete']:
                self.download_items(key,provider,event,credentials)
                self.update(key,audio_complete=1)
            check_cancel(event)
            if not import_playlists.reconcile(self,key,credentials,event,self.playlist_client):
                if row['index_attempts']>=19:raise ImportFailure('SOURCE_UNAVAILABLE')
                self.update(key,state='queued',phase='indexing',current=None,next_attempt=time.time()+30,index_attempts=row['index_attempts']+1)
                deferred=True;return
            result=self.view(row['uid'],key)
            self.update(key,state='finished_with_errors' if result['failed'] or result['unavailable'] or result['missingPlaylistTracks'] else 'completed',phase='finished',current=None,finished=now(),error=None)
        except Cancelled:
            self.update(key,state='paused_auth' if self.stop.is_set() else 'cancelled',phase='paused' if self.stop.is_set() else 'finished',current=None,
                error='AUTH_REQUIRED' if self.stop.is_set() else 'CANCELLED',finished=None if self.stop.is_set() else now())
        except BaseException as error:
            code=error_code(error)
            self.update(key,state='paused_auth' if code=='AUTH_REQUIRED' else 'failed',phase='paused',current=None,error=code,finished=None if code=='AUTH_REQUIRED' else now())
        finally:
            if provider is not None:
                with contextlib.suppress(Exception):provider.close()
            credentials.clear()
            if not deferred:
                with self.lock:self.credentials.pop(key,None);self.events.pop(key,None)

    def download_items(self,key,provider,event,credentials):
        self.update(key,phase='classification')
        with self.db() as db:pending=[r[0] for r in db.execute("SELECT id FROM items WHERE job=? AND kind='pending' ORDER BY rowid",(key,))]
        for offset in range(0,len(pending),100):
            check_cancel(event);batch=pending[offset:offset+100]
            records=self._retry(lambda:provider.metadata(batch),event)
            with self.db() as db:
                for track in batch:
                    meta=records.get(track)
                    if meta is None:raise ImportFailure('SOURCE_UNAVAILABLE')
                    db.execute('UPDATE items SET kind=?,meta=? WHERE job=? AND id=?',
                        ('music' if meta and meta['kind']=='music' else 'excluded',json.dumps(meta or {'id':track,'kind':'excluded'}),key,track))
                db.execute('UPDATE jobs SET updated=? WHERE id=?',(now(),key))
        self.update(key,phase='download')
        with self.db() as db:items=[dict(r) for r in db.execute("SELECT * FROM items WHERE job=? AND kind='music' ORDER BY rowid",(key,))]
        for item in items:
            check_cancel(event)
            if item['outcome']=='unavailable':continue
            meta=json.loads(item['meta'])
            self.update(key,current=json.dumps({'sourceId':item['id'],'title':meta.get('title',''),'artist':meta.get('artist','')}))
            try:
                if meta.get('available') is False:
                    self._item(key,item['id'],outcome='unavailable',error='SOURCE_UNAVAILABLE',bytes=0);continue
                self._one(key,item['id'],provider,event,credentials)
            except ImportFailure as error:
                if error.code in ('AUTH_REQUIRED','NO_SPACE'):raise
                self._item(key,item['id'],outcome='unavailable' if error.unavailable else 'failed',error=error.code,bytes=0)
            if event.wait(1):raise Cancelled()


    def _loop(self):
        while not self.stop.is_set():
            with self.db() as db:row=db.execute("SELECT id FROM jobs WHERE state='queued' AND next_attempt<=? ORDER BY updated LIMIT 1",(time.time(),)).fetchone()
            if row:self.run_job(row['id'])
            else:self.wake.wait(1);self.wake.clear()

    def close(self):
        self.stop.set();self.wake.set()
        with self.lock:
            for event in self.events.values():event.set()
        if self.worker:self.worker.join(timeout=30)
        self.credentials.clear()
        if self.worker_lock:self.worker_lock.close()

def read_payload(handler):
    data=bytearray()
    for block in audio.body_chunks(handler.rfile,handler.headers):
        if len(data)+len(block)>16*1024:raise ApiError(413,'REQUEST_TOO_LARGE')
        data.extend(block)
    try:value=json.loads(data)
    except (ValueError,UnicodeError):raise ApiError(400,'INVALID_REQUEST') from None
    if not isinstance(value,dict) or set(value)!={'oauthToken'}:raise ApiError(400,'INVALID_REQUEST')
    return value['oauthToken']

def dispatch(handler,manager):
    import urllib.parse
    parsed=urllib.parse.urlsplit(handler.path)
    if not parsed.path.startswith(PREFIX):return None
    try:
        if parsed.query:raise ApiError(400,'INVALID_REQUEST')
        identity=profile.authenticate(handler.headers)
        tail=parsed.path[len(PREFIX):]
        if handler.command=='POST' and tail=='':
            result=manager.schedule(identity,read_payload(handler));status=202
        elif handler.command=='GET' and tail=='/current':result=manager.view(identity['id']);status=200
        else:
            parts=tail.lstrip('/').split('/')
            if not tail.startswith('/') or not parts or len(parts)>2:
                raise ApiError(404,'NOT_FOUND')
            try:
                if str(uuid.UUID(parts[0]))!=parts[0]:raise ValueError()
            except ValueError:raise ApiError(404,'NOT_FOUND') from None
            if handler.command=='GET' and len(parts)==1:result=manager.view(identity['id'],parts[0]);status=200
            elif handler.command=='POST' and parts[1:] == ['cancel']:
                result=manager.cancel(identity['id'],parts[0]);status=202
            elif handler.command=='POST' and parts[1:] == ['resume']:
                result=manager.schedule(identity,read_payload(handler),parts[0]);status=202
            else:raise ApiError(405,'METHOD_NOT_ALLOWED')
        handler.result(status,{'job':result});return status
    except profile.ProfileError as error:
        status=401 if error.status==401 else 503
        handler.result(status,{'error':{'code':'AUTH_REQUIRED' if status==401 else 'INTERNAL_ERROR','message':'Import authentication unavailable'}});return status
    except ImportFailure as error:
        status=503 if error.code=='NO_SPACE' else 422
        handler.result(status,{'error':{'code':error.code,'message':'Import request rejected'}});return status
    except (ApiError,audio.Rejected) as error:
        status=error.status;code=error.code if isinstance(error,ApiError) else 'INVALID_REQUEST'
        handler.result(status,{'error':{'code':code,'message':'Import request rejected'}});return status
    except (OSError,ValueError,sqlite3.Error):
        handler.result(503,{'error':{'code':'INTERNAL_ERROR','message':'Import temporarily unavailable'}});return 503
