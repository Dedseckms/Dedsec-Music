#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Create a regular all-library account with private-playlist isolation checks."""
import argparse
import datetime
import getpass
import json
import os
import re
import sys
import urllib.parse
import kit

def create(username):
    if not sys.stdin.isatty():raise RuntimeError('Use an interactive host terminal')
    owner=json.loads((kit.ROOT/'data/bootstrap/owner.ready').read_text())
    with kit.database() as db:
        if db.execute('SELECT 1 FROM user WHERE user_name=? COLLATE NOCASE',(username,)).fetchone():raise RuntimeError('Username already exists; no existing user is changed')
        if db.execute('SELECT count(*) FROM playlist WHERE owner_id=? AND public=1',(owner['id'],)).fetchone()[0]:raise RuntimeError('Owner has public playlists; make them private in Navidrome before creating an isolated account')
        libraries=[r[0] for r in db.execute('SELECT id FROM library ORDER BY id')]
        private=[r[0] for r in db.execute('SELECT id FROM playlist WHERE owner_id=? AND public=0',(owner['id'],))]
    if not libraries:raise RuntimeError('No music libraries found')
    secret=kit.password('New user password')
    owner_secret=getpass.getpass('Existing administrator password (hidden): ')
    admin=kit.required('/auth/login',{'username':owner['username'],'password':owner_secret});owner_secret=''
    if admin.get('id')!=owner['id'] or admin.get('isAdmin') is not True:raise RuntimeError('Existing owner administrator required')
    folder=kit.ROOT/'backups'/('before-user-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(mode=0o700,parents=True);kit.backup_db(folder)
    entity=kit.required('/api/user',{'userName':username,'name':username,'password':secret,'isAdmin':False},admin['token'],'POST')
    uid=entity['id'];encoded=urllib.parse.quote(uid,safe='')
    # No random password or privilege escalation; an interrupted creation remains a regular account.
    kit.required('/api/user/'+encoded+'/library',{'libraryIds':libraries},admin['token'],'PUT')
    auth=kit.required('/auth/login',{'username':username,'password':secret});secret=''
    own=kit.required('/api/user/'+encoded,token=auth['token'])
    if auth.get('id')!=uid or auth.get('isAdmin') is not False or own.get('isAdmin') is not False or sorted(x['id'] for x in own.get('libraries',[]))!=sorted(libraries):raise RuntimeError('Regular role/library verification failed; no public-ready claim')
    for key in private:
        for suffix in ('','/tracks'):
            code,_=kit.api('/api/playlist/'+urllib.parse.quote(key,safe='')+suffix,token=auth['token'])
            if code not in (403,404):raise RuntimeError('Private owner playlist ACL verification failed')
    print('Regular account ready; all current libraries assigned; private owner playlists remain inaccessible.')

if __name__=='__main__':
    os.umask(0o077);parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--username',required=True);args=parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]{3,32}',args.username):parser.error('Use3-32 Latin letters, digits, dot, underscore or dash')
    try:create(args.username)
    except Exception as error:
        print('User setup stopped: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__)+'. Credentials were not logged.',file=sys.stderr);raise SystemExit(1)
