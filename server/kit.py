#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Private local setup utilities; credentials never enter command arguments or logs."""
import contextlib
import getpass
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parent
NAV='http://127.0.0.1:14533'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
HTTP=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())

def config():
    values={}
    for line in (ROOT/'.env').read_text().splitlines():
        if not line.strip() or line.lstrip().startswith('#'):continue
        key,separator,value=line.partition('=')
        if not separator or key not in {'MUSIC_DOMAIN','ACME_EMAIL','STACK_NAME','LOCAL_UID','LOCAL_GID'} or key in values:
            raise RuntimeError('Use only the variables shown in .env.example')
        values[key]=value.strip()
    domain=values.get('MUSIC_DOMAIN','')
    if not re.fullmatch(r'(?=.{1,253}$)(?:[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?\.)+[A-Za-z]{2,63}',domain) or domain.endswith('example.com'):
        raise RuntimeError('Set your real public DNS domain in .env')
    if not re.fullmatch(r'[^\s@{}$]+@[^\s@{}$]+\.[A-Za-z]{2,63}',values.get('ACME_EMAIL','')):
        raise RuntimeError('Set a valid ACME_EMAIL')
    if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{2,40}',values.get('STACK_NAME','')):
        raise RuntimeError('STACK_NAME must be a unique lowercase Compose project name')
    for key in ['LOCAL_UID','LOCAL_GID']:
        if not values.get(key,'').isdigit() or not 1<=int(values[key])<2**31:
            raise RuntimeError('Use your non-root numeric UID and GID')
    return values

def compose(*args,check=True,**kwargs):
    values=config()
    environment=dict(os.environ);environment.update(values)
    # Shell variables must not override the validated domain/project/non-root IDs.
    kwargs['env']=environment
    return subprocess.run(['docker','compose','--project-directory',str(ROOT),'--project-name',values['STACK_NAME'],
        '--env-file',str(ROOT/'.env'),'-f',str(ROOT/'compose.yml'),*args],check=check,**kwargs)

def project_guard():
    values=config()
    result=subprocess.run(['docker','ps','-a','--filter','label=com.docker.compose.project='+values['STACK_NAME'],
        '--format','{{.Label "com.docker.compose.project.working_dir"}}'],capture_output=True,text=True,check=True,timeout=15)
    if any(Path(line).resolve()!=ROOT for line in result.stdout.splitlines()):
        raise RuntimeError('STACK_NAME belongs to a different directory; choose another project name')

def free_port(port,udp=False):
    for family,address in ((socket.AF_INET,'0.0.0.0'),(socket.AF_INET6,'::')):
        try:
            with socket.socket(family,socket.SOCK_DGRAM if udp else socket.SOCK_STREAM) as probe:
                if family==socket.AF_INET6:probe.setsockopt(socket.IPPROTO_IPV6,socket.IPV6_V6ONLY,1)
                probe.bind((address,port))
        except OSError as error:
            if family==socket.AF_INET6 and error.errno in (97,10047):continue
            raise RuntimeError('Port '+str(port)+(' UDP' if udp else ' TCP')+' is occupied; existing services were not changed') from None

def api(path,payload=None,token=None,method=None):
    headers={'Content-Type':'application/json'}
    if token:headers['X-ND-Authorization']='Bearer '+token
    request=urllib.request.Request(NAV+path,data=json.dumps(payload).encode() if payload is not None else None,
        headers=headers,method=method)
    try:
        with HTTP.open(request,timeout=15) as response:
            raw=response.read(2**20+1)
            if len(raw)>2**20:raise RuntimeError('Native metadata response is too large')
            return response.status,json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:return error.code,None

def required(path,payload=None,token=None,method=None):
    status,data=api(path,payload,token,method)
    if status not in (200,201):raise RuntimeError('Native API rejected operation (HTTP '+str(status)+')')
    return data

def password(label):
    value=getpass.getpass(label+' (hidden, 12+ characters): ')
    if len(value)<12 or len(value.encode())>72 or value!=value.strip():raise RuntimeError('Use 12+ characters, at most72 UTF-8 bytes, without surrounding spaces')
    if value!=getpass.getpass('Repeat password (hidden): '):raise RuntimeError('Passwords differ')
    return value

@contextlib.contextmanager
def database():
    path=ROOT/'data/navidrome/navidrome.db'
    db=sqlite3.connect(path.resolve().as_uri()+'?mode=ro',uri=True,timeout=1)
    db.row_factory=sqlite3.Row
    try:yield db
    finally:db.close()

def online_backup(source_path,target):
    if source_path.is_symlink() or not source_path.is_file() or target.exists():raise RuntimeError('Backup needs a physical source and a new target')
    deadline=time.monotonic()+45
    def progress(*args):
        if time.monotonic()>deadline:raise RuntimeError('Online backup timed out; incomplete copy retained privately')
    with contextlib.closing(sqlite3.connect(source_path.resolve().as_uri()+'?mode=ro',uri=True,timeout=1)) as source,contextlib.closing(sqlite3.connect(target)) as copy:
        source.backup(copy,pages=256,progress=progress,sleep=0.05)
        if copy.execute('PRAGMA quick_check').fetchall()!=[('ok',)]:raise RuntimeError('SQLite backup quick_check failed')
    target.chmod(0o600)
    digest=hashlib.sha256()
    with target.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
    return digest.hexdigest()

def backup_db(folder):return online_backup(ROOT/'data/navidrome/navidrome.db',folder/'navidrome.sqlite')
