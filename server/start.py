#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Start loopback services, initialize the owner privately, then enable HTTPS."""
import getpass
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import kit

def initialize_owner():
    username=input('Administrator login: ').strip()
    if not re.fullmatch(r'[A-Za-z0-9_.-]{3,32}',username):raise RuntimeError('Use3-32 Latin letters, digits, dot, underscore or dash')
    secret=kit.password('Administrator password')
    status,auth=kit.api('/auth/login',{'username':username,'password':secret})
    if status not in (200,201):
        # Login-first also recovers a createAdmin success whose response was lost.
        kit.api('/auth/createAdmin',{'username':username,'password':secret})
        status,auth=kit.api('/auth/login',{'username':username,'password':secret})
    secret=''
    if status!=200 or not isinstance(auth,dict) or auth.get('isAdmin') is not True:
        raise RuntimeError('Owner setup not verified; public proxy remains off')
    return {'id':auth['id'],'username':username}

def main():
    os.umask(0o077)
    if os.name!='posix' or not sys.stdin.isatty():raise RuntimeError('Run on the Linux Docker host in your interactive terminal')
    values=kit.config()
    if int(values['LOCAL_UID'])!=os.getuid() or int(values['LOCAL_GID'])!=os.getgid():raise RuntimeError('LOCAL_UID/GID must match id -u and id -g of this non-root user')
    subprocess.run(['docker','compose','version'],check=True,timeout=15)
    subprocess.run(['docker','info','--format','{{.ServerVersion}}'],check=True,timeout=15)
    kit.project_guard()
    kit.compose('config','--quiet',timeout=15)
    for port in (80,443):kit.free_port(port)
    kit.free_port(443,udp=True)
    result=kit.compose('ps','--status','running','--services',capture_output=True,text=True,timeout=15)
    running=set(result.stdout.splitlines())
    for service,port in [('navidrome',14533),('api',18083)]:
        if service not in running:kit.free_port(port)
    if shutil.disk_usage(kit.ROOT).free<10*2**30+100_000_000:raise RuntimeError('At least10GiB reserve plus100MB is required')
    for name in ['navidrome','api','bootstrap','music','music/library','music/staging','caddy','caddy-config']:
        path=kit.ROOT/'data'/name
        path.mkdir(mode=0o700,parents=True,exist_ok=True)
        if path.is_symlink() or path.resolve()!=path:raise RuntimeError('Data paths must be real directories inside this kit')
    kit.compose('up','-d','--build','navidrome','api',timeout=900)
    for _ in range(90):
        try:
            with kit.HTTP.open(kit.NAV+'/ping',timeout=2) as response:
                if response.status==200:break
        except OSError:pass
        time.sleep(1)
    else:raise RuntimeError('Navidrome not ready; public proxy remains off')
    for _ in range(30):
        try:
            with kit.HTTP.open('http://127.0.0.1:18083/health',timeout=2) as response:
                if response.status==200:break
        except OSError:pass
        time.sleep(1)
    else:raise RuntimeError('Music API not ready; public proxy remains off')
    owner=initialize_owner()
    marker=kit.ROOT/'data/bootstrap/owner.ready'
    if marker.exists():
        if marker.is_symlink() or json.loads(marker.read_text())!=owner:raise RuntimeError('Existing owner marker differs; not overwritten')
    else:
        with marker.open('x') as output:json.dump(owner,output)
    for port in (80,443):kit.free_port(port)
    kit.free_port(443,udp=True)
    kit.compose('--profile','public','up','-d','caddy',timeout=120)
    print('Ready: https://'+values['MUSIC_DOMAIN'])
    print('Use this address and the same explicitly entered account for listening and uploads.')

if __name__=='__main__':
    try:main()
    except (OSError,RuntimeError,ValueError,subprocess.SubprocessError,KeyboardInterrupt) as error:
        print('Setup stopped: '+(str(error) if isinstance(error,RuntimeError) else type(error).__name__)+'. Existing services/data were not deleted.',file=sys.stderr)
        raise SystemExit(1)
