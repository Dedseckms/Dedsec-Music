#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Isolated pinned Navidrome lyrics/JWT/SQL proof; never uses installed accounts."""
import argparse
import contextlib
import datetime
from email.message import Message
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import wave

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'api'))
import lyrics_api
import profile_api as profile
from radio_catalog import Catalog


def main(binary,parent):
    os.umask(0o077);folder=parent/('lyrics-native-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'));folder.mkdir(mode=0o700,parents=True)
    library=folder/'music';library.mkdir();audio=library/'fixture.wav'
    with wave.open(str(audio),'wb') as stream:
        stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(8000);stream.writeframes(b'\0\0'*16000)
    (library/'fixture.lrc').write_text('[00:00.10]First source line\n[00:01.20]Second source line\n',encoding='utf-8')
    with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    config=folder/'navidrome.toml'
    config.write_text('Address="127.0.0.1"\nPort='+str(port)+'\nMusicFolder='+json.dumps(str(library))+
                      '\nDataFolder='+json.dumps(str(folder/'nav-state'))+'\nLogLevel="error"\nAutoImportPlaylists=false\n'
                      'EnableInsightsCollector=false\nEnableExternalServices=false\n[Scanner]\nSchedule="0"\n')
    profile.NAV='http://127.0.0.1:'+str(port);password=secrets.token_urlsafe(32)
    def api(path,payload):
        import urllib.request
        request=urllib.request.Request(profile.NAV+path,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with profile.HTTP.open(request,timeout=5) as result:return json.loads(result.read(65536))
    with (folder/'native.log').open('xb') as log:
        child=subprocess.Popen([str(binary),'--configfile',str(config)],stdin=subprocess.DEVNULL,stdout=log,stderr=log)
        try:
            for _ in range(90):
                if child.poll() is not None:raise RuntimeError('Native fixture stopped')
                try:
                    with profile.HTTP.open(profile.NAV+'/ping',timeout=1) as r:
                        if r.status==200:break
                except OSError:pass
                time.sleep(.2)
            else:raise RuntimeError('Native readiness timeout')
            api('/auth/createAdmin',{'username':'fixture-owner','password':password})
            auth=api('/auth/login',{'username':'fixture-owner','password':password});headers=Message();headers['Authorization']='Bearer '+auth['token']
            identity=profile.authenticate(headers)
            for _ in range(90):
                songs,_=profile.native(auth['token'],'/api/song?_start=0&_end=10')
                if songs:break
                time.sleep(.2)
            else:raise RuntimeError('Native fixture song not indexed')
            catalog=Catalog(folder/'nav-state/navidrome.db',{'1':library})
            manager=lyrics_api.Manager(folder/'api-state',catalog,start=False,external=lambda song:lyrics_api.response('not_found'))
            song,key=manager.song(identity['id'],songs[0]['id'])
            value=lyrics_api.native_lyrics(identity,song)
            expected=[{'startMs':100,'text':'First source line'},{'startMs':1200,'text':'Second source line'}]
            if value is None or value['lines']!=expected or value['synced'] is not True:raise RuntimeError('Native structured lyrics/time/POST JWT contract differed')
            pending=manager.get(identity,song['id'])
            if pending['status']!='pending':raise RuntimeError('Lazy lookup not pending')
            item=manager.work.get_nowait();manager.process(item);manager.pending.discard(item[2]);manager.work.task_done()
            if manager.get(identity,song['id'])['lines']!=expected:raise RuntimeError('Cache result differed')
            with manager.db() as db:
                if auth['token'] in ''.join(str(row) for row in db.execute('SELECT * FROM cache')):raise RuntimeError('Credential reached cache')
            (folder/'proof.json').write_text(json.dumps({'navVersion':'0.64.2','nativeJWT':True,'postFormLyrics':True,'sidecarIndexed':True,
                    'scopedMetadataSQL':True,'exactSourceTimestamps':True,'lazyPending':True,'cachedResult':True,'noTokenOnDisk':True},indent=2))
            print('PASS native0.64.2: JWT POST lyrics, scoped metadata, actual sidecar timestamps, lazy pending/cache, no cached token. Fixture:',folder)
        finally:
            password=''
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--parent',type=Path,required=True)
    args=parser.parse_args();main(args.binary.resolve(),args.parent.resolve())
