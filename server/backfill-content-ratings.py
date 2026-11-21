#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Bounded metadata-only Yandex backfill using proven download IDs and file hashes.

No Navidrome writes, audio tagging, rescans, account mutations or service changes.
The operator explicitly supplies private state/token paths; output contains counts only.
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import sys
import threading
import time

sys.path.insert(0,str(Path(__file__).resolve().parent/'api'))
from content_ratings import Store
from radio_catalog import Catalog


def digest(path,bytes_per_second=16*1024*1024):
    result=hashlib.sha256()
    started=time.monotonic();count=0
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):
            result.update(block);count+=len(block)
            delay=count/bytes_per_second-(time.monotonic()-started)
            if delay>0:time.sleep(delay)
    return result.hexdigest()


def candidates(catalog,downloads):
    """Bind exact proven provider IDs and paths; never match titles or artists."""
    songs=catalog.songs();by_path={}
    for song in songs.values():by_path.setdefault(song['path'],[]).append(song)
    with contextlib.closing(sqlite3.connect(Path(downloads).resolve().as_uri()+'?mode=ro',uri=True)) as db:
        for key,path,size,sha,origin in db.execute('SELECT id,path,bytes,sha,origin FROM downloads ORDER BY id LIMIT 50001'):
            if origin not in ('legacy_verified','published') or len(by_path.get(path,[]))!=1:continue
            song=by_path[path][0]
            if re.fullmatch(r'(?:[0-9]{1,20}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})',str(key)) is None:continue
            prefix='tracks/' if origin=='legacy_verified' else 'yandex/'
            if path not in {prefix+str(key)+suffix for suffix in ('.mp3','.m4a','.flac')}:continue
            if type(size) is not int or size<=0 or not isinstance(sha,str) or re.fullmatch('[a-f0-9]{64}',sha) is None:continue
            yield song,str(key),size,sha


def run(args,provider_factory=None):
    os.umask(0o077)
    catalog=Catalog(args.nav_db,{'1':args.library_root})
    counts={'eligible':0,'alreadyCurrent':0,'hashRejected':0,'fileChanged':0,'clean':0,'explicit':0,'unknown':0,'requests':0}
    if not args.apply:
        counts['eligible']=sum(1 for _ in candidates(catalog,args.downloads_db))
        print(json.dumps(counts));return counts
    token=args.token_file
    info=token.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:
        raise ValueError('Private owned token required')
    secret=token.read_text().strip()
    if not secret or len(secret)>4096:raise ValueError('Invalid private token')
    store=Store(args.state_root,catalog)
    # Exactly one backfill process, independent of the live gateway and audio workers.
    import fcntl
    with (store.path.parent/'backfill.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        backup=store.path.parent/('before-backfill-'+str(time.time_ns())+'.sqlite')
        with store.db() as source,contextlib.closing(sqlite3.connect(backup)) as target:source.backup(target)
        if provider_factory is None:
            from yandex_source import Provider
            provider_factory=Provider
        provider=provider_factory(secret,threading.Event());secret=None
        try:
            batch=[]
            def flush():
                counts['requests']+=1
                metadata=provider.metadata([key for _,key,_,_ in batch])
                for song,key,signature,_ in batch:
                    rating=(metadata.get(key) or {}).get('explicitStatus','')
                    if rating not in ('explicit','clean'):rating=''
                    try:store.put_verified(song,key,rating,signature)
                    except ValueError:counts['fileChanged']+=1;continue
                    counts[rating or 'unknown']+=1
                batch.clear()
                print(json.dumps(counts),flush=True)
                time.sleep(args.interval)
            for song,key,size,checksum in candidates(catalog,args.downloads_db):
                counts['eligible']+=1
                signature=store.signature(song)
                if signature is None or signature[2]!=size:counts['hashRejected']+=1;continue
                if store.has_receipt(song,key,signature):counts['alreadyCurrent']+=1;continue
                physical=catalog.physical(song)
                if physical is None or digest(physical[0],getattr(args,'read_mib_per_sec',16)*1024*1024)!=checksum:counts['hashRejected']+=1;continue
                if store.signature(song)!=signature:counts['fileChanged']+=1;continue
                batch.append((song,key,signature,checksum))
                if len(batch)==100:
                    flush()
                    if counts['requests']>=args.max_batches:break
            if batch and counts['requests']<args.max_batches:flush()
        finally:provider.close()
    return counts


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for option in ('nav-db','library-root','downloads-db','state-root','token-file'):
        parser.add_argument('--'+option,type=Path,required=True)
    parser.add_argument('--apply',action='store_true')
    parser.add_argument('--max-batches',type=int,default=1)
    parser.add_argument('--interval',type=float,default=1.0)
    parser.add_argument('--read-mib-per-sec',type=float,default=16)
    args=parser.parse_args()
    if not 1<=args.max_batches<=500 or not 1<=args.interval<=60:parser.error('Bounded batches1..500 and interval1..60 required')
    if not 1<=args.read_mib_per_sec<=64:parser.error('Read budget1..64MiB/s required')
    try:run(args)
    except Exception as error:
        print('backfill failed: '+type(error).__name__,file=sys.stderr)
        raise SystemExit(1) from None


if __name__=='__main__':main()
