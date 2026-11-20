# SPDX-License-Identifier: GPL-3.0-or-later
"""Owner-controlled resumable metadata-only lyrics discovery on the lazy fetch worker."""
import email.utils
import json
import os
import stat
import time
import urllib.error

import profile_api as profile

MAX_SONGS=100000
PACE=2


class Batch:
    def __init__(self,manager):
        self.manager=manager
        with manager.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS lyrics_batch_control(
              id INTEGER PRIMARY KEY CHECK(id=1),owner TEXT NOT NULL,enabled INTEGER NOT NULL,
              cursor TEXT NOT NULL,discovering INTEGER NOT NULL,total INTEGER NOT NULL,next_fetch REAL NOT NULL)''')
            db.execute('''CREATE TABLE IF NOT EXISTS lyrics_batch_song(
              song TEXT PRIMARY KEY,version TEXT NOT NULL,status TEXT NOT NULL,seen INTEGER NOT NULL)''')

    def authorize(self,identity):
        self.manager.guard(identity)
        if not self.manager.protected_owner or identity['id']!=self.manager.protected_owner:raise profile.ProfileError(403)

    def owner_identity(self,owner):
        # Trusted jobs never call a JWT guard. Fail closed until the host wires its
        # local revocation policy; the current native row is still checked here.
        if not owner or owner!=self.manager.protected_owner or self.manager.background_guard is None:raise profile.ProfileError(403)
        with self.manager.catalog.db() as db:
            row=db.execute('SELECT id,user_name,is_admin FROM user WHERE id=?',(owner,)).fetchone()
        if row is None or row['is_admin']!=1:raise profile.ProfileError(403)
        identity={'id':row['id'],'username':row['user_name'],'isAdmin':True}
        self.manager.background_guard(identity)
        return identity

    def status(self,identity):
        self.authorize(identity)
        return self._status(identity['id'])

    def status_trusted(self,owner):
        self.owner_identity(owner)
        return self._status(owner)

    def _status(self,owner):
        identity={'id':owner}
        with self.manager.db() as db:
            row=db.execute('SELECT owner,enabled,discovering,total FROM lyrics_batch_control WHERE id=1').fetchone()
            counts=dict(db.execute('SELECT status,count(*) FROM lyrics_batch_song GROUP BY status')) if row and row[0]==identity['id'] else {}
        if not row or row[0]!=identity['id']:row=(identity['id'],0,0,0)
        checked=sum(counts.get(key,0) for key in ('synced','plain','instrumental','not_found','error'))
        pending=max(0,row[3]-checked)
        return {'total':row[3],'checked':checked,'synced':counts.get('synced',0),'plain':counts.get('plain',0),
                'instrumental':counts.get('instrumental',0),'notFound':counts.get('not_found',0),
                'errors':counts.get('error',0),'pending':pending,'running':bool(row[1] and (row[2] or pending)),
                'discovering':bool(row[2])}

    def control(self,identity,action):
        self.authorize(identity)
        return self.apply_control(identity,action)

    def control_trusted(self,owner,action):
        return self.apply_control(self.owner_identity(owner),action)

    def apply_control(self,identity,action):
        if action not in ('start','pause'):raise profile.ProfileError(400)
        with self.manager.lock:
            if action=='start':
                with self.manager.catalog.db() as db:
                    total=db.execute('''SELECT count(*) FROM media_file m WHERE m.missing=0 AND EXISTS(
                      SELECT 1 FROM user_library u WHERE u.user_id=? AND u.library_id=m.library_id)''',(identity['id'],)).fetchone()[0]
                if total>MAX_SONGS:raise profile.ProfileError(413)
                with self.manager.db() as db:
                    row=db.execute('SELECT owner,next_fetch FROM lyrics_batch_control WHERE id=1').fetchone()
                    if row and row[0]!=identity['id']:db.execute('DELETE FROM lyrics_batch_song')
                    db.execute("UPDATE lyrics_batch_song SET seen=0,status=CASE WHEN status='error' THEN 'pending' ELSE status END")
                    # A rescan retains completed physical versions and discovers new/replaced songs.
                    db.execute('INSERT OR REPLACE INTO lyrics_batch_control VALUES(1,?,1,?,1,?,?)',
                               (identity['id'],'',total,row[1] if row else 0))
            else:
                with self.manager.db() as db:db.execute('UPDATE lyrics_batch_control SET enabled=0 WHERE id=1 AND owner=?',(identity['id'],))
        return self._status(identity['id'])

    def row(self):
        with self.manager.db() as db:
            return db.execute('SELECT owner,enabled,cursor,discovering,total,next_fetch FROM lyrics_batch_control WHERE id=1').fetchone()

    def discover(self,row):
        owner,enabled,cursor,discovering,total,next_fetch=row
        with self.manager.catalog.db() as db:
            keys=[item[0] for item in db.execute('''SELECT m.id FROM media_file m WHERE m.missing=0 AND m.id>? AND EXISTS(
              SELECT 1 FROM user_library u WHERE u.user_id=? AND u.library_id=m.library_id) ORDER BY m.id LIMIT 128''',(cursor,owner))]
        versions=[]
        for key in keys:
            try:_,version=self.manager.song(owner,key)
            except profile.ProfileError:version='unavailable'
            versions.append((key,version))
        with self.manager.lock:
            if self.row()!=row:return
            with self.manager.db() as db:
                for key,version in versions:
                    db.execute('''INSERT INTO lyrics_batch_song VALUES(?,?,?,1) ON CONFLICT(song) DO UPDATE SET
                      seen=1,status=CASE WHEN lyrics_batch_song.version=excluded.version THEN lyrics_batch_song.status ELSE excluded.status END,version=excluded.version''',
                      (key,version,'pending' if version!='unavailable' else 'error'))
                if not keys:
                    db.execute('DELETE FROM lyrics_batch_song WHERE seen=0')
                    total=db.execute('SELECT count(*) FROM lyrics_batch_song').fetchone()[0]
                    db.execute('UPDATE lyrics_batch_control SET total=? WHERE id=1',(total,))
                db.execute('UPDATE lyrics_batch_control SET cursor=?,discovering=? WHERE id=1',
                           (keys[-1] if keys else cursor,int(bool(keys))))

    def defer(self,seconds,provider=False):
        when=time.time()+max(PACE,seconds)
        if provider:self.manager.external_retry=max(self.manager.external_retry,when)
        with self.manager.db() as db:db.execute('UPDATE lyrics_batch_control SET next_fetch=max(next_fetch,?) WHERE id=1',(when,))

    def sidecar(self,song):
        """Only an adjacent physical LRC; no embedded tag scan or network credentials."""
        from lyrics_api import MAX_TEXT,response,timed_lines
        physical=self.manager.catalog.physical(song)
        if physical is None:return None
        path=physical[0].with_suffix('.lrc')
        try:
            if path.is_symlink() or path.resolve()!=path:return None
            fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0))
        except (FileNotFoundError,OSError):return None
        with os.fdopen(fd,'rb') as stream:
            info=os.fstat(stream.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size>MAX_TEXT:return None
            raw=stream.read(MAX_TEXT+1)
        try:lines=timed_lines(raw.decode('utf-8-sig'),song['duration'])
        except (ValueError,UnicodeError):return None
        return response('ready',lines,True,'LRC') if lines else None

    def step(self):
        """One bounded unit, called only while no foreground request is queued."""
        from lyrics_api import TTL,response
        row=self.row()
        if not row or not row[1] or not self.manager.protected_owner or row[0]!=self.manager.protected_owner:return
        try:identity=self.owner_identity(row[0])
        except profile.ProfileError:
            with self.manager.db() as db:db.execute('UPDATE lyrics_batch_control SET enabled=0 WHERE id=1')
            return
        if row[3]:self.discover(row);return
        if time.time()<max(row[5],self.manager.external_retry):return
        with self.manager.db() as db:
            item=db.execute("SELECT song,version FROM lyrics_batch_song WHERE status='pending' ORDER BY song LIMIT 1").fetchone()
        if not item:return
        key,version=item
        try:
            self.owner_identity(row[0])
            song,current=self.manager.song(row[0],key)
            if version!=current:
                with self.manager.db() as db:db.execute("UPDATE lyrics_batch_song SET version=?,status='pending' WHERE song=?",(current,key))
                return
            with self.manager.db() as db:
                cached=db.execute('SELECT value FROM cache WHERE key=? AND expires>?',(version,time.time())).fetchone()
            value=json.loads(cached[0]) if cached else self.sidecar(song)
            if value is None:
                self.defer(PACE)
                value=self.manager.fetch_external(song,background=True)
                self.defer(PACE)
            try:self.owner_identity(row[0])
            except profile.ProfileError:
                with self.manager.db() as db:db.execute('UPDATE lyrics_batch_control SET enabled=0 WHERE id=1')
                return
            if self.manager.song(row[0],key)[1]!=version:return
        except urllib.error.HTTPError as error:
            if error.code==429:
                raw=(error.headers or {}).get('Retry-After','60')
                try:delay=float(raw) if len(raw)<=128 and raw.isdigit() else email.utils.parsedate_to_datetime(raw).timestamp()-time.time()
                except (ValueError,TypeError,OverflowError):delay=60
                self.defer(max(1,delay),provider=True);return
            value=response('error')
        except profile.ProfileError:
            # Removed library access cannot publish a result from the old visibility snapshot.
            value=response('error')
        except (OSError,ValueError,TypeError,KeyError):value=response('error')
        status=('synced' if value.get('synced') else 'plain') if value['status']=='ready' else value['status']
        if status not in ('synced','plain','instrumental','not_found','error'):status='error';value=response('error')
        with self.manager.lock:
            latest=self.row()
            if not latest or not latest[1] or latest[0]!=row[0]:return
            with self.manager.db() as db:
                stamp=time.time()
                db.execute('INSERT INTO cache VALUES(?,?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,expires=excluded.expires,updated=excluded.updated',(version,json.dumps(value,separators=(',',':')),stamp+TTL[value['status']],stamp))
                db.execute('UPDATE lyrics_batch_song SET status=? WHERE song=? AND version=?',(status,key,version))
                self.manager.prune(db)
