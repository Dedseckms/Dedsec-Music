#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Consistent private Navidrome DB backup plus avatars/bootstrap and optional immutable audio."""
import argparse
import datetime
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import kit

def copy_tree(source,destination):
    if source.is_symlink():raise RuntimeError('Backup refuses symbolic links')
    destination.mkdir(mode=0o700)
    for path in source.iterdir():
        if path.is_symlink():raise RuntimeError('Backup refuses symbolic links')
        target=destination/path.name
        if path.is_dir():copy_tree(path,target)
        elif path.is_file():
            if path.name.endswith(('-wal','-shm')) or path.name=='worker.lock':continue
            if path.suffix in ('.sqlite','.db'):
                kit.online_backup(path,target);continue
            fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
            with os.fdopen(fd,'rb') as src,target.open('xb') as dst:
                if not stat.S_ISREG(os.fstat(src.fileno()).st_mode):raise RuntimeError('Backup input is not a regular file')
                shutil.copyfileobj(src,dst,1024*1024)
            target.chmod(0o600)
        else:raise RuntimeError('Backup encountered unsupported file type')

def main(with_music=False):
    os.umask(0o077);kit.project_guard()
    folder=kit.ROOT/'backups'/('snapshot-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(mode=0o700,parents=True)
    checksum=kit.backup_db(folder)
    # Avatars are atomically replaced; each copied JPEG is a complete old or new version.
    for name in ['api','bootstrap']:
        source=kit.ROOT/'data'/name
        if source.exists():copy_tree(source,folder/name)
    if with_music:copy_tree(kit.ROOT/'data/music/library',folder/'music')
    # Persisted TLS material is streamed directly to a private file, never to the terminal.
    result=kit.compose('ps','--status','running','--services',capture_output=True,text=True,timeout=15)
    tls=False
    if 'caddy' in result.stdout.splitlines():
        with (folder/'caddy-data.tar').open('xb') as output:
            kit.compose('exec','-T','caddy','tar','-C','/data','-cf','-','.',stdout=output,stderr=subprocess.DEVNULL,timeout=60)
        tls=True
    (folder/'manifest.json').write_text(json.dumps({'databaseSHA256':checksum,'sqliteQuickCheck':'ok',
        'musicIncluded':with_music,'tlsIncluded':tls},indent=2))
    print('Verified private backup:',folder)
    if not with_music:print('Audio is not in this backup. Keep a separate music copy or use --with-music.')

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--with-music',action='store_true');args=parser.parse_args()
    try:main(args.with_music)
    except Exception as error:
        print('Backup incomplete: '+type(error).__name__+'. No source data was deleted; retain the partial private folder for review.',file=sys.stderr);raise SystemExit(1)
