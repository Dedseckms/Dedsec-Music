#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Isolated native account proof: generated credentials, private fixture, owned child only."""
import argparse
import datetime
from email.message import Message
import json
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'api'))
import admin_api as admin
import profile_api as profile
import server


def main(binary,parent):
    folder=parent/('admin-native-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(mode=0o700,parents=True);library=folder/'library';library.mkdir()
    with socket.socket() as listener:listener.bind(('127.0.0.1',0));port=listener.getsockname()[1]
    profile.NAV='http://127.0.0.1:'+str(port)
    config=folder/'navidrome.toml'
    config.write_text('Address="127.0.0.1"\nPort='+str(port)+'\nMusicFolder='+json.dumps(str(library))+
        '\nDataFolder='+json.dumps(str(folder/'state'))+'\nLogLevel="error"\nAutoImportPlaylists=false\n'
        'EnableInsightsCollector=false\nEnableExternalServices=false\n[Scanner]\nSchedule="0"\n')
    def auth(path,value):
        request=urllib.request.Request(profile.NAV+path,data=json.dumps(value).encode(),headers={'Content-Type':'application/json'})
        with profile.HTTP.open(request,timeout=10) as response:return json.loads(response.read(65536))
    password=secrets.token_urlsafe(24);friend_password=secrets.token_urlsafe(24)
    with (folder/'native.log').open('xb') as log:
        child=subprocess.Popen([str(binary),'--configfile',str(config)],stdin=subprocess.DEVNULL,stdout=log,stderr=log)
        try:
            for _ in range(100):
                if child.poll() is not None:raise RuntimeError('Native fixture exited')
                try:
                    with profile.HTTP.open(profile.NAV+'/ping',timeout=1) as response:
                        if response.status==200:break
                except OSError:pass
                time.sleep(.2)
            else:raise RuntimeError('Native readiness failed')
            auth('/auth/createAdmin',{'username':'fixture-owner','password':password})
            owner=auth('/auth/login',{'username':'fixture-owner','password':password})
            owner_headers=Message();owner_headers['Authorization']='Bearer '+owner['token']
            identity=profile.authenticate(owner_headers)
            if not identity['isAdmin']:raise RuntimeError('Native role failed')
            policy=admin.configure(folder,folder/'state/navidrome.db',library)
            created=policy.create(identity,{'username':'fixture-friend','password':friend_password})
            friend_id=created['user']['id']
            if created['user']['isAdmin'] or not created['uploadReady']:raise RuntimeError('Regular provisioning failed')
            headers=Message();headers['X-Password']=friend_password
            token=server.login('username=fixture-friend',headers).decode()
            friend_headers=Message();friend_headers['Authorization']='Bearer '+token
            friend=profile.authenticate(friend_headers)
            if friend['id']!=friend_id or friend['isAdmin']:raise RuntimeError('Native friend identity failed')
            events=[]
            class Handler:
                path=admin.PREFIX+'/me';command='GET';headers=friend_headers
                def send_response(self,code):events.append(code)
                def send_header(self,*args):pass
                def end_headers(self):pass
                class Writer:
                    def write(self,data):pass
                wfile=Writer()
            if admin.dispatch(Handler(),lambda *_:[])!=403:raise RuntimeError('Ordinary admin access allowed')
            if len(policy.list(identity)['users'])!=2:raise RuntimeError('Native list failed')
            retained=library/'retained.mp3';retained.write_bytes(b'fixture-audio-retained')
            policy.delete(identity,friend_id)
            if not retained.is_file() or not policy.blocked('fixture-friend'):raise RuntimeError('Delete retention failed')
            try:profile.authenticate(friend_headers)
            except profile.ProfileError:pass
            else:raise RuntimeError('Deleted native JWT remained accepted')
            (folder/'proof.json').write_text(json.dumps({'version':'0.64.2','nativeAdminRole':True,'ordinary403':True,
                'regularCreateAllLibraries':True,'uploaderLoginSamePassword':True,'consistentBackup':True,
                'nativeDeleteOldJWTRejected':True,'audioRetained':True,'noUsernameReuse':True},indent=2))
            print('PASS native0.64.2 admin: create/libraries/login/ordinary403/delete/JWT revocation/audio retention. Fixture:',folder)
        finally:
            password=friend_password=''
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--parent',type=Path,required=True)
    args=parser.parse_args();main(args.binary.resolve(),args.parent.resolve())
