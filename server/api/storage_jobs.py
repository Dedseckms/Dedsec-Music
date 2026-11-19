# SPDX-License-Identifier: GPL-3.0-or-later
"""Registered-volume copy jobs. Never mount, format, delete sources or edit Navidrome."""
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import stat
import threading
import time
import uuid

RESERVE=10*2**30
BLOCK=256*1024
ACTIVE=('queued','running','paused','awaiting_activation')


class StorageError(Exception):
    def __init__(self,status,code):self.status,self.code=status,code


def physical(path):
    path=Path(path)
    if not path.is_absolute() or path.is_symlink() or path.resolve()!=path:raise StorageError(409,'UNSAFE_PATH')
    return path


def stamp(info):return (info.st_dev,info.st_ino,info.st_size,info.st_mtime_ns)


class Manager:
    def __init__(self,state,library,volumes=None,slot=None,rate=4*2**20,start=True,nav_database=None,library_ids=('1',)):
        self.library=physical(library);self.folder=Path(state)/'storage';self.folder.mkdir(mode=0o700,parents=True,exist_ok=True);physical(self.folder)
        self.path=self.folder/'jobs.sqlite';self.slot=slot;self.rate=max(256*1024,min(16*2**20,int(rate)))
        self.nav_database=Path(nav_database) if nav_database else None;self.library_ids=tuple(str(x) for x in library_ids)
        if not 1<=len(self.library_ids)<=64:raise ValueError('Configured library IDs required')
        self.summary={};self.summary_at=0
        if self.path.is_symlink():raise ValueError('Physical storage database required')
        self.volumes={'primary':{'id':'primary','label':'Primary','root':self.library,'expectedDevice':self.library.stat().st_dev,'target':False}}
        for item in volumes or []:
            key=item.get('id','')
            if not isinstance(key,str) or not 1<=len(key)<=40 or not all(c.isascii() and (c.isalnum() or c in '_-') for c in key) or key in self.volumes:raise ValueError('Unique volume ID required')
            root=Path(item['root'])
            if not root.is_absolute() or self.library==root or self.library in root.parents:raise ValueError('Target must be outside active library')
            if type(item.get('expectedDevice')) is not int:raise ValueError('Pinned target device required')
            self.volumes[key]={'id':key,'label':str(item.get('label',key))[:80],'root':root,'expectedDevice':item['expectedDevice'],'target':True}
        self.lock=threading.RLock();self.db_lock=threading.RLock();self.plan_lock=threading.Lock();self.wake=threading.Event();self.stop=threading.Event();self.cancelled=threading.Event();self.active=None;self.thread=None
        self.file_lock=None
        if start:
            import fcntl
            fd=os.open(self.folder/'worker.lock',os.O_RDWR|os.O_CREAT|os.O_NOFOLLOW,0o600)
            info=os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or info.st_nlink!=1:os.close(fd);raise ValueError('Private storage lock required')
            self.file_lock=os.fdopen(fd,'a');fcntl.flock(self.file_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        with self.db() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS plans(id TEXT PRIMARY KEY,hash TEXT,shard TEXT NOT NULL,target TEXT NOT NULL,created REAL NOT NULL,expires REAL NOT NULL,files INTEGER NOT NULL,bytes INTEGER NOT NULL);
              CREATE TABLE IF NOT EXISTS files(plan TEXT NOT NULL,rel TEXT NOT NULL,dev INTEGER,ino INTEGER,size INTEGER,mtime INTEGER,digest TEXT,done INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(plan,rel));
              CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,plan TEXT NOT NULL UNIQUE,uid TEXT NOT NULL,state TEXT NOT NULL,phase TEXT NOT NULL,files_done INTEGER NOT NULL DEFAULT 0,bytes_done INTEGER NOT NULL DEFAULT 0,error TEXT,updated REAL NOT NULL);''')
            db.execute("UPDATE jobs SET state='paused',error='RESTARTED',updated=? WHERE state IN ('running','queued')",(time.time(),))
        self.path.chmod(0o600)
        if start:
            self.thread=threading.Thread(target=self.loop,name='storage-copy',daemon=True);self.thread.start()

    @contextlib.contextmanager
    def db(self):
        with self.db_lock:
            db=sqlite3.connect(self.path,timeout=.5);db.row_factory=sqlite3.Row
            try:
                with db:yield db
            finally:db.close()

    def shard(self,key):
        if key not in ('tracks','yandex','uploads'):raise StorageError(400,'UNKNOWN_SHARD')
        path=physical(self.library/key)
        if not path.is_dir():raise StorageError(404,'UNKNOWN_SHARD')
        return path

    def volume(self,key):
        if key not in self.volumes:raise StorageError(400,'UNKNOWN_VOLUME')
        return self.volumes[key]

    def volume_status(self,key):
        config=self.volume(key);root=config['root'];reason=None;usage=None
        try:
            physical(root)
            if not root.is_dir() or root.stat().st_dev!=config['expectedDevice']:reason='MISSING_MOUNT'
            else:
                usage=shutil.disk_usage(root)
                if not config['target']:reason='NOT_CONFIGURED'
                elif root.stat().st_dev==self.library.stat().st_dev:reason='SAME_FILESYSTEM'
                elif not self.supported_filesystem(root):reason='UNSUPPORTED_FILESYSTEM'
                elif not os.access(root,os.W_OK):reason='READ_ONLY'
                elif usage.free<RESERVE:reason='INSUFFICIENT_SPACE'
        except (OSError,StorageError):reason='MISSING_MOUNT'
        return {'id':key,'label':config['label'],'mountPoint':str(root),'totalBytes':usage.total if usage else None,
            'usedBytes':usage.used if usage else None,'availableBytes':usage.free if usage else None,
            'readOnly':reason=='READ_ONLY','eligibleTarget':reason is None,'reason':reason}

    @staticmethod
    def supported_filesystem(root):
        """Copies need durable POSIX no-replace links; do not assume removable FAT/NTFS."""
        try:
            best=None
            for line in Path('/proc/self/mountinfo').read_text().splitlines():
                before,after=line.split(' - ',1)
                raw=before.split()[4]
                for old,new in (('\\040',' '),('\\011','\t'),('\\012','\n'),('\\134','\\')):raw=raw.replace(old,new)
                mount=Path(raw)
                if mount==root or mount in root.parents:
                    if best is None or len(mount.parts)>best[0]:best=(len(mount.parts),after.split()[0])
            return best is not None and best[1] in ('ext4','xfs','btrfs','zfs')
        except (OSError,ValueError,IndexError):return False

    def inventory(self):
        self.refresh_summary()
        shards=[]
        for key in ('tracks','yandex','uploads'):
            if (self.library/key).is_dir() and not (self.library/key).is_symlink():
                count,size=self.summary.get(key,(None,None))
                shards.append({'id':key,'label':key,'volumeId':'primary','logicalPath':key,'trackCount':count,'indexedBytes':size})
        with self.db() as db:row=db.execute("SELECT id FROM jobs WHERE state IN ('queued','running','paused','awaiting_activation') ORDER BY updated DESC LIMIT 1").fetchone()
        return {'volumes':[self.volume_status(key) for key in self.volumes],'shards':shards,'activeJob':self.view(row[0]) if row else None}

    def refresh_summary(self):
        if self.nav_database is None or time.monotonic()-self.summary_at<60:return
        with self.lock:
            if time.monotonic()-self.summary_at<60:return
            self.summary_at=time.monotonic()
            try:
                physical(self.nav_database)
                with contextlib.closing(sqlite3.connect(self.nav_database.as_uri()+'?mode=ro',uri=True,timeout=.2)) as db:
                    deadline=time.monotonic()+.5;db.execute('PRAGMA query_only=ON');db.set_progress_handler(lambda:int(time.monotonic()>deadline),1000)
                    query="SELECT substr(path,1,instr(path,'/')-1),count(*),coalesce(sum(size),0) FROM media_file WHERE missing=0 AND library_id IN ("+','.join('?' for _ in self.library_ids)+') GROUP BY 1'
                    self.summary={key:(count,size) for key,count,size in db.execute(query,self.library_ids) if key in ('tracks','yandex','uploads')}
            except (OSError,sqlite3.Error,StorageError):self.summary={}

    def snapshot(self,root,deadline):
        count=0;stack=[root]
        while stack:
            parent=physical(stack.pop())
            with os.scandir(parent) as entries:
                for item in entries:
                    if time.monotonic()>deadline:raise StorageError(409,'PLAN_TIMEOUT')
                    if item.is_symlink():raise StorageError(409,'UNSAFE_PATH')
                    path=Path(item.path);info=os.stat(path,follow_symlinks=False)
                    if stat.S_ISDIR(info.st_mode):stack.append(path)
                    elif stat.S_ISREG(info.st_mode) and info.st_nlink==1:
                        count+=1
                        if count>100000:raise StorageError(422,'PLAN_LIMIT')
                        yield path.relative_to(root).as_posix(),stamp(info)
                    else:raise StorageError(409,'UNSAFE_PATH')

    def prepare(self,shard,target):
        if not self.plan_lock.acquire(blocking=False):raise StorageError(429,'STORAGE_BUSY')
        key=str(uuid.uuid4())
        spool=self.folder/('.planning-'+key+'.sqlite');spool_inode=None
        try:
            root=self.shard(shard);status=self.volume_status(target)
            if not status['eligibleTarget']:raise StorageError(409,status['reason'])
            descriptor=os.open(spool,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600)
            spool_inode=os.fstat(descriptor).st_ino;os.close(descriptor)
            # A long directory inventory must never hold the worker ledger's write lock.
            with contextlib.closing(sqlite3.connect(spool)) as staging:
                staging.execute('CREATE TABLE files(rel TEXT PRIMARY KEY,dev INTEGER,ino INTEGER,size INTEGER,mtime INTEGER)')
                files=total=metadata=0
                with staging:
                    for rel,values in self.snapshot(root,time.monotonic()+10):
                        metadata+=len(rel.encode('utf-8'))
                        if metadata>16*2**20:raise StorageError(422,'PLAN_LIMIT')
                        staging.execute('INSERT INTO files VALUES(?,?,?,?,?)',(rel,*values));files+=1;total+=values[2]
                if not files:raise StorageError(409,'EMPTY_SHARD')
                if status['availableBytes']<total+RESERVE:raise StorageError(507,'NO_SPACE')
                digest=hashlib.sha256()
                for row in staging.execute('SELECT rel,dev,ino,size,mtime FROM files ORDER BY rel'):
                    digest.update(json.dumps(tuple(row),separators=(',',':')).encode())
                plan_hash=hashlib.sha256((digest.hexdigest()+'|'+shard+'|'+target).encode()).hexdigest()
            with self.db() as db:
                db.execute('ATTACH DATABASE ? AS snapshot',(str(spool),))
                expired=[r[0] for r in db.execute('SELECT id FROM plans WHERE expires<? AND id NOT IN (SELECT plan FROM jobs)',(time.time(),))]
                for old in expired:db.execute('DELETE FROM files WHERE plan=?',(old,));db.execute('DELETE FROM plans WHERE id=?',(old,))
                if db.execute('SELECT count(*) FROM plans').fetchone()[0]>=32:raise StorageError(429,'PLAN_LIMIT')
                db.execute('INSERT INTO plans VALUES(?,?,?,?,?,?,?,?)',(key,plan_hash,shard,target,time.time(),time.time()+1800,files,total))
                db.execute('INSERT INTO files(plan,rel,dev,ino,size,mtime) SELECT ?,rel,dev,ino,size,mtime FROM snapshot.files',(key,))
                expires=db.execute('SELECT expires FROM plans WHERE id=?',(key,)).fetchone()[0]
            return {'id':key,'planHash':plan_hash,'files':files,'bytes':total,'reserveBytes':RESERVE,'expiresAt':expires,
                    'requiresOperatorActivation':True,'warnings':['SOURCES_RETAINED','OPERATOR_BIND_MOUNT_REQUIRED','NO_SPACE_FREED_BEFORE_ACTIVATION']}
        finally:
            try:
                if spool_inode is not None and spool.exists() and spool.lstat().st_ino==spool_inode:spool.unlink()
            finally:self.plan_lock.release()

    def execute(self,uid,key,checksum):
        with self.lock,self.db() as db:
            row=db.execute('SELECT * FROM plans WHERE id=?',(key,)).fetchone()
            if row is None:raise StorageError(404,'PLAN_NOT_FOUND')
            if row['hash']!=checksum:raise StorageError(409,'STALE_PLAN')
            old=db.execute('SELECT id FROM jobs WHERE plan=?',(key,)).fetchone()
            if old:return self.view(old[0])
            if row['expires']<time.time():raise StorageError(409,'STALE_PLAN')
            if db.execute("SELECT 1 FROM jobs WHERE state IN ('queued','running','paused','awaiting_activation')").fetchone():raise StorageError(409,'STORAGE_BUSY')
            job=str(uuid.uuid4());db.execute('INSERT INTO jobs(id,plan,uid,state,phase,updated) VALUES(?,?,?,?,?,?)',(job,key,uid,'queued','copying',time.time()))
        self.wake.set();return self.view(job)

    def view(self,key):
        with self.db() as db:row=db.execute('SELECT j.*,p.files,p.bytes FROM jobs j JOIN plans p ON p.id=j.plan WHERE j.id=?',(key,)).fetchone()
        if row is None:raise StorageError(404,'JOB_NOT_FOUND')
        return {'id':key,'state':row['state'],'phase':row['phase'],'filesTotal':row['files'],'filesDone':row['files_done'],
            'bytesTotal':row['bytes'],'bytesCopied':row['bytes_done'],'errorCode':row['error'],'updatedAt':row['updated']}

    def action(self,key,action):
        with self.lock,self.db() as db:
            row=db.execute('SELECT * FROM jobs WHERE id=?',(key,)).fetchone()
            if row is None:raise StorageError(404,'JOB_NOT_FOUND')
            if action=='cancel':
                if row['state']=='awaiting_activation':raise StorageError(409,'ACTIVATION_PENDING')
                if self.active==key:self.cancelled.set()
                db.execute("UPDATE jobs SET state='cancelled',error=NULL,updated=? WHERE id=?",(time.time(),key))
            elif action=='resume':
                if row['state'] not in ('paused','cancelled','failed') or self.active is not None:raise StorageError(409,'STORAGE_BUSY')
                if db.execute("SELECT 1 FROM jobs WHERE id!=? AND state IN ('queued','running','paused','awaiting_activation')",(key,)).fetchone():raise StorageError(409,'STORAGE_BUSY')
                db.execute("UPDATE jobs SET state='queued',error=NULL,updated=? WHERE id=?",(time.time(),key))
            else:raise StorageError(400,'INVALID_REQUEST')
        self.wake.set();return self.view(key)

    def update(self,key,**values):
        if set(values)-{'state','phase','error','files_done','bytes_done'}:raise ValueError('Unknown storage field')
        with self.db() as db:db.execute('UPDATE jobs SET '+','.join(k+'=?' for k in values)+',updated=? WHERE id=?',(*values.values(),time.time(),key))

    def check(self):
        if self.stop.is_set():raise StorageError(409,'RESTARTED')
        if self.cancelled.is_set():raise StorageError(409,'CANCELLED')

    def read_block(self,stream,maximum=BLOCK):
        self.check()
        while self.slot is not None and not self.slot.acquire(timeout=.2):self.check()
        started=time.monotonic()
        try:block=stream.read(min(BLOCK,maximum))
        finally:
            if self.slot is not None:self.slot.release()
        if block:self.stop.wait(max(0,len(block)/self.rate-(time.monotonic()-started)))
        self.check();return block

    def safe_directory(self,path,root):
        physical(root)
        if root not in path.parents and path!=root:raise StorageError(409,'UNSAFE_PATH')
        current=root
        for part in path.relative_to(root).parts:
            current=current/part
            try:current.mkdir(mode=0o700)
            except FileExistsError:pass
            physical(current)
            info=current.stat()
            if not stat.S_ISDIR(info.st_mode) or (hasattr(os,'getuid') and info.st_uid!=os.getuid()) or (os.name!='nt' and info.st_mode&0o022):raise StorageError(409,'UNSAFE_PATH')

    @staticmethod
    def owned_part(path,flags):
        fd=os.open(path,flags|getattr(os,'O_NOFOLLOW',0),0o600)
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or (hasattr(os,'getuid') and info.st_uid!=os.getuid()) or (os.name!='nt' and stat.S_IMODE(info.st_mode)!=0o600):
            os.close(fd);raise StorageError(409,'UNSAFE_PATH')
        return fd

    def copy_file(self,job,plan,row,destination):
        root=self.shard(plan['shard']);path=physical(root/row['rel'])
        expected=tuple(row[k] for k in ('dev','ino','size','mtime'))
        if stamp(path.stat())!=expected:raise StorageError(409,'SOURCE_CHANGED')
        final=destination/'data'/plan['shard']/row['rel'];part=destination/'staging'/(hashlib.sha256(row['rel'].encode()).hexdigest()+'.part')
        self.safe_directory(final.parent,destination);self.safe_directory(part.parent,destination)
        if final.exists():
            if not row['digest'] or self.digest(final)!=row['digest']:raise StorageError(409,'TARGET_CONFLICT')
            return
        if part.is_symlink():raise StorageError(409,'UNSAFE_PATH')
        source=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0));digest=hashlib.sha256()
        with os.fdopen(source,'rb') as src:
            info=os.fstat(src.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or stamp(info)!=expected:raise StorageError(409,'SOURCE_CHANGED')
            offset=part.stat().st_size if part.exists() else 0
            if offset>row['size']:raise StorageError(409,'TARGET_CONFLICT')
            if offset:
                with os.fdopen(self.owned_part(part,os.O_RDONLY),'rb') as old:
                    remaining=offset
                    while remaining:
                        block=self.read_block(src,remaining);actual=old.read(len(block))
                        if not block or block!=actual:raise StorageError(409,'TARGET_CONFLICT')
                        digest.update(block);remaining-=len(block)
            flags=os.O_WRONLY|os.O_APPEND|getattr(os,'O_NOFOLLOW',0)
            descriptor=self.owned_part(part,flags if part.exists() else flags|os.O_CREAT|os.O_EXCL)
            with os.fdopen(descriptor,'ab') as output:
                if not stat.S_ISREG(os.fstat(output.fileno()).st_mode) or os.fstat(output.fileno()).st_nlink!=1:raise StorageError(409,'UNSAFE_PATH')
                remaining=row['size']-offset
                while remaining:
                    block=self.read_block(src,remaining)
                    if not block:raise StorageError(409,'SOURCE_CHANGED')
                    if shutil.disk_usage(destination).free<RESERVE+len(block):raise StorageError(507,'NO_SPACE')
                    output.write(block);digest.update(block);remaining-=len(block)
                output.flush();os.fsync(output.fileno())
            if stamp(os.fstat(src.fileno()))!=expected or stamp(path.stat())!=expected:raise StorageError(409,'SOURCE_CHANGED')
        value=digest.hexdigest()
        if self.digest(part)!=value:raise StorageError(409,'INTEGRITY_FAILED')
        # Durable intent before atomic no-replace publication gives an idempotent crash retry.
        with self.db() as db:db.execute('UPDATE files SET digest=? WHERE plan=? AND rel=?',(value,plan['id'],row['rel']))
        self.check()
        try:os.link(part,final,follow_symlinks=False)
        except FileExistsError:
            if self.digest(final)!=value:raise StorageError(409,'TARGET_CONFLICT')
        if os.name=='posix':
            fd=os.open(final.parent,os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW)
            try:os.fsync(fd)
            finally:os.close(fd)
        # Remove only this job's private staging link; sources are always read-only.
        part.unlink()

    def digest(self,path):
        physical(path)
        descriptor=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0));value=hashlib.sha256()
        with os.fdopen(descriptor,'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):raise StorageError(409,'UNSAFE_PATH')
            while True:
                block=self.read_block(stream)
                if not block:return value.hexdigest()
                value.update(block)

    def run(self,key):
        with self.lock:
            if self.active is not None:raise StorageError(409,'STORAGE_BUSY')
            self.active=key;self.cancelled.clear()
        try:
            with self.db() as db:
                job=db.execute('SELECT * FROM jobs WHERE id=?',(key,)).fetchone();plan=db.execute('SELECT * FROM plans WHERE id=?',(job['plan'],)).fetchone()
            if job['state']!='queued':return
            status=self.volume_status(plan['target'])
            if not status['eligibleTarget']:raise StorageError(409,status['reason'])
            self.validate_source(plan)
            volume=self.volume(plan['target']);base=physical(volume['root']);destination=base/'dedsec-music-copies'/key
            self.safe_directory(destination,base);self.update(key,state='running',phase='copying',error=None)
            with self.db() as db:count,total=db.execute('SELECT count(*),coalesce(sum(size),0) FROM files WHERE plan=? AND done=1',(plan['id'],)).fetchone()
            self.update(key,files_done=count,bytes_done=total)
            last=''
            while True:
                with self.db() as db:rows=db.execute('SELECT * FROM files WHERE plan=? AND rel>? ORDER BY rel LIMIT 128',(plan['id'],last)).fetchall()
                if not rows:break
                for row in rows:
                    self.check()
                    if physical(base).stat().st_dev!=volume['expectedDevice']:raise StorageError(409,'MISSING_MOUNT')
                    self.copy_file(job,plan,row,destination)
                    with self.db() as db:
                        changed=db.execute('UPDATE files SET done=1 WHERE plan=? AND rel=? AND done=0',(plan['id'],row['rel'])).rowcount
                    if changed:count+=1;total+=row['size']
                    self.update(key,files_done=count,bytes_done=total)
                    last=row['rel']
            self.update(key,phase='verifying')
            self.validate_source(plan)
            with self.lock:
                self.check()
                if self.view(key)['state']=='cancelled':raise StorageError(409,'CANCELLED')
                self.update(key,state='awaiting_activation',phase='awaiting_activation')
        except StorageError as error:
            current=self.view(key)
            if current['state']!='cancelled':self.update(key,state='paused' if error.code=='RESTARTED' else 'failed',error=error.code)
        except (OSError,sqlite3.Error,ValueError):
            if self.view(key)['state']!='cancelled':self.update(key,state='failed',error='IO_ERROR')
        finally:
            with self.lock:self.active=None

    def validate_source(self,plan):
        seen=0
        with self.db() as db:
            for rel,values in self.snapshot(self.shard(plan['shard']),time.monotonic()+10):
                self.check();row=db.execute('SELECT dev,ino,size,mtime FROM files WHERE plan=? AND rel=?',(plan['id'],rel)).fetchone()
                if row is None or tuple(row)!=values:raise StorageError(409,'SOURCE_CHANGED')
                seen+=1
            if seen!=plan['files']:raise StorageError(409,'SOURCE_CHANGED')

    def loop(self):
        if hasattr(os,'nice'):
            with contextlib.suppress(OSError):os.nice(19)
        while not self.stop.is_set():
            try:
                with self.db() as db:
                    # Only this singleton thread can own running work. Recover a failed error write.
                    db.execute("UPDATE jobs SET state='paused',error='IO_ERROR',updated=? WHERE state='running'",(time.time(),))
                    row=db.execute("SELECT id FROM jobs WHERE state='queued' ORDER BY updated LIMIT 1").fetchone()
                if row:self.run(row[0])
                else:self.wake.wait(1);self.wake.clear()
            except (OSError,sqlite3.Error,StorageError,ValueError):self.stop.wait(1)

    def close(self):
        self.stop.set();self.wake.set()
        if self.thread:self.thread.join(timeout=6)
        if self.file_lock:self.file_lock.close()
