#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline explicit migration of verified legacy source IDs; never copies or changes audio."""
import argparse
import collections
import contextlib
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import sys

import kit


def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()


def candidates(connection,library,manifest):
    """Only old downloader records, approved source IDs and exact physical hash matches."""
    allowed={str(value) for section in manifest['sections'] for value in section['track_ids']}
    result=[];reasons=collections.Counter()
    for key,path,size,checksum,seconds in connection.execute('SELECT id,path,bytes,sha256,seconds FROM downloads'):
        key=str(key)
        if key not in allowed:reasons['outsideApprovedManifest']+=1;continue
        if not re.fullmatch(r'(?:[0-9]{1,20}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})',key):reasons['invalidID']+=1;continue
        if path not in {'tracks/'+key+suffix for suffix in ('.mp3','.m4a','.flac')}:reasons['unprovenPath']+=1;continue
        target=library/path
        if target.is_symlink() or target.resolve()!=target or not target.is_file():reasons['notPhysicalFile']+=1;continue
        if (not isinstance(size,int) or size<=0 or target.stat().st_size!=size or not isinstance(checksum,str)
                or not re.fullmatch('[0-9a-f]{64}',checksum) or not isinstance(seconds,(int,float))
                or not math.isfinite(seconds) or seconds<=0):reasons['invalidRecordedEvidence']+=1;continue
        if sha(target)!=checksum:reasons['hashMismatch']+=1;continue
        result.append((key,path,size,checksum,seconds,'legacy_verified'))
    return result,dict(reasons)


def main(args):
    import fcntl
    os.umask(0o077)
    library=args.library_root.resolve();state=args.state_root.resolve();root=state/'imports'
    if args.library_root.is_symlink() or not library.is_dir() or state.is_symlink():raise RuntimeError('Physical private roots required')
    if args.legacy_db.is_symlink() or not args.legacy_db.is_file():raise RuntimeError('Physical legacy SQLite required')
    root.mkdir(mode=0o700,parents=True,exist_ok=True)
    if root.resolve()!=root:raise RuntimeError('Physical importer state required')
    with (root/'worker.lock').open('a') as lock:
        try:fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BlockingIOError:raise RuntimeError('Stop only the importer API before offline seeding; active worker detected') from None
        with contextlib.closing(sqlite3.connect(args.legacy_db.resolve().as_uri()+'?mode=ro',uri=True,timeout=1)) as old:
            old.execute('BEGIN')
            rows,reasons=candidates(old,library,json.loads(args.manifest.read_text(encoding='utf-8')))
        report={'verifiedSourceIDs':len(rows),'rejected':reasons,'audioChanged':False,'applied':args.apply}
        target=root/'jobs.sqlite'
        if target.is_symlink():raise RuntimeError('Importer DB must not be a symlink')
        if args.apply:
            folder=state/'seed-backups'/datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f');folder.mkdir(mode=0o700,parents=True)
            kit.online_backup(args.legacy_db,folder/'legacy.sqlite')
            if target.exists():kit.online_backup(target,folder/'imports.sqlite')
            with contextlib.closing(sqlite3.connect(target,timeout=1)) as db,db:
                db.execute("CREATE TABLE IF NOT EXISTS downloads(id TEXT PRIMARY KEY,path TEXT NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,seconds REAL NOT NULL,origin TEXT NOT NULL DEFAULT 'published')")
                for row in rows:
                    existing=db.execute('SELECT id,path,bytes,sha,seconds,origin FROM downloads WHERE id=?',(row[0],)).fetchone()
                    if existing is not None and existing!=row:raise RuntimeError('Existing source evidence differs; transaction rolled back')
                    db.execute('INSERT OR IGNORE INTO downloads VALUES(?,?,?,?,?,?)',row)
            target.chmod(0o600)
            (folder/'report.json').write_text(json.dumps(report,indent=2))
            report['backup']=str(folder)
        print(json.dumps(report,indent=2))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['state-root','library-root','legacy-db','manifest']:parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    try:main(args)
    except Exception as error:
        print('Seed stopped: '+type(error).__name__+'. No audio was changed.',file=sys.stderr);raise SystemExit(1)
