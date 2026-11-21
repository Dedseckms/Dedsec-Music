# SPDX-License-Identifier: GPL-3.0-or-later
"""Trusted provider ratings bound to exact current physical files; no audio writes."""
import contextlib
from pathlib import Path
import re
import sqlite3
import stat
import time

import profile_api as profile


def status(value):
    return {'e':'explicit','c':'clean','explicit':'explicit','clean':'clean'}.get(value,'')


def provider_status(track):
    warning=getattr(track,'content_warning',None)
    explicit=getattr(track,'explicit',None)
    if explicit is True or warning=='explicit':return 'explicit'
    if explicit is False and warning in (None,''):return 'clean'
    return ''


def request_ids(body):
    if not isinstance(body,dict) or set(body)!={'ids'} or not isinstance(body['ids'],list) or len(body['ids'])>500:
        raise profile.ProfileError(400)
    if any(not isinstance(key,str) or re.fullmatch(r'[A-Za-z0-9_-]{1,80}',key) is None for key in body['ids']):
        raise profile.ProfileError(400)
    return list(dict.fromkeys(body['ids']))


class Store:
    def __init__(self,state,catalog):
        folder=Path(state)/'content-ratings';folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        if folder.is_symlink() or folder.resolve()!=folder:raise ValueError('Physical rating state required')
        self.path=folder/'ratings.sqlite';self.catalog=catalog
        if self.path.is_symlink():raise ValueError('Physical ratings database required')
        with self.db() as db:
            db.execute('''CREATE TABLE IF NOT EXISTS ratings(song TEXT PRIMARY KEY,library TEXT NOT NULL,path TEXT NOT NULL,
              bytes INTEGER NOT NULL,mtime INTEGER NOT NULL,device INTEGER NOT NULL,inode INTEGER NOT NULL,
              source TEXT NOT NULL,source_id TEXT NOT NULL,status TEXT NOT NULL,updated REAL NOT NULL)''')
            db.execute('''CREATE TABLE IF NOT EXISTS overrides(song TEXT PRIMARY KEY,library TEXT NOT NULL,path TEXT NOT NULL,
              bytes INTEGER NOT NULL,mtime INTEGER NOT NULL,device INTEGER NOT NULL,inode INTEGER NOT NULL,
              status TEXT NOT NULL,actor TEXT NOT NULL,updated REAL NOT NULL)''')
        self.path.chmod(0o600)

    @contextlib.contextmanager
    def db(self):
        with contextlib.closing(sqlite3.connect(self.path,timeout=.5)) as db:
            with db:yield db

    def signature(self,song):
        physical=self.catalog.physical(song)
        if physical is None:return None
        path,size,mtime=physical
        info=path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_size!=size or info.st_mtime_ns!=mtime:return None
        return (str(song['library_id']),song['path'],size,mtime,info.st_dev,info.st_ino)

    def enrich(self,songs):
        rows={};overrides={}
        keys=list(songs)
        with self.db() as db:
            for start in range(0,len(keys),500):
                batch=keys[start:start+500]
                if batch:
                    for row in db.execute('SELECT song,library,path,bytes,mtime,device,inode,status FROM ratings WHERE song IN ('+','.join('?' for _ in batch)+')',batch):rows[row[0]]=row
                    for row in db.execute('SELECT song,library,path,bytes,mtime,device,inode,status FROM overrides WHERE song IN ('+','.join('?' for _ in batch)+')',batch):overrides[row[0]]=row
        for key,song in songs.items():
            native=status(song.get('explicit_status',''))
            row=rows.get(key);override=overrides.get(key);overlay='';manual=''
            signature=self.signature(song) if row or override or native=='clean' else None
            if native=='clean' and signature is None:native=''
            if row and tuple(row[1:7])==signature:overlay=status(row[7])
            if override and tuple(override[1:7])==signature:manual=status(override[7])
            # Explicit evidence always wins over contradictory clean metadata.
            song['explicitStatus']=manual or ('explicit' if 'explicit' in (native,overlay) else native or overlay)
            song['contentRatingOverride']=bool(manual)
        return songs

    def query(self,uid,body):
        keys=request_ids(body)
        songs=self.catalog.entries(uid,keys)
        self.enrich(songs)
        return {'ratings':{key:song['explicitStatus'] for key,song in songs.items() if song.get('explicitStatus')},
                'overrides':{key:song['explicitStatus'] for key,song in songs.items() if song.get('contentRatingOverride')}}

    def override(self,identity,body):
        """Only a current native administrator may change one visible file's manual rating."""
        import admin_api
        if admin_api.SERVICE is None:raise profile.ProfileError(503)
        user=admin_api.SERVICE.identity(identity)
        if user.get('id')!=identity['id'] or user.get('isAdmin') is not True:raise profile.ProfileError(403)
        if not isinstance(body,dict) or set(body)!={'id','status'} or body['status'] not in ('clean','explicit','source'):
            raise profile.ProfileError(400)
        key=request_ids({'ids':[body['id']]})[0]
        songs=self.catalog.entries(identity['id'],[key]);song=songs.get(key)
        if song is None:raise profile.ProfileError(404)
        signature=self.signature(song)
        if signature is None:raise profile.ProfileError(404)
        # Repeat the live role/revocation check immediately before the durable write.
        user=admin_api.SERVICE.identity(identity)
        if user.get('id')!=identity['id'] or user.get('isAdmin') is not True:raise profile.ProfileError(403)
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            current=self.catalog.entries(identity['id'],[key]).get(key)
            if current is None or self.signature(current)!=signature:raise profile.ProfileError(409)
            if body['status']=='source':db.execute('DELETE FROM overrides WHERE song=?',(key,))
            else:db.execute('INSERT OR REPLACE INTO overrides VALUES(?,?,?,?,?,?,?,?,?,?)',
                (key,*signature,body['status'],identity['id'],time.time()))
        return self.query(identity['id'],{'ids':[key]})

    def put_verified(self,song,source_id,rating,signature):
        """Caller proves provider source binding; refuse any file replacement during lookup."""
        if rating not in ('explicit','clean','') or re.fullmatch(r'(?:[0-9]{1,20}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})',source_id) is None:
            raise ValueError('Known trusted provider rating required')
        if signature is None or self.signature(song)!=signature:raise ValueError('Rating file changed')
        with self.db() as db:
            previous=db.execute('SELECT library,path,bytes,mtime,device,inode,status FROM ratings WHERE song=?',(song['id'],)).fetchone()
            if not rating and previous and tuple(previous[:6])==signature and previous[6]=='explicit':rating='explicit'
            db.execute('INSERT OR REPLACE INTO ratings VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (song['id'],*signature,'yandex',source_id,rating,time.time()))

    def has_receipt(self,song,source_id,signature):
        """Resume without repeat provider calls/hashing while the same file is unchanged."""
        with self.db() as db:
            row=db.execute('SELECT library,path,bytes,mtime,device,inode,source_id FROM ratings WHERE song=?',(song['id'],)).fetchone()
        return row is not None and tuple(row[:6])==signature and row[6]==source_id

