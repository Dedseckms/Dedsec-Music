#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Optional isolated Navidrome0.64.2 proof; creates only a new private fixture folder."""
import argparse
import contextlib
import datetime
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.request
import wave

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'api'))
import import_playlists as playlists
import profile_api as profile


def main(binary,parent):
    os.umask(0o077)
    folder=parent/('import-native-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
    folder.mkdir(mode=0o700,parents=True)
    library=folder/'library';(library/'yandex').mkdir(parents=True)
    for number in ['1','2']:
        with wave.open(str(library/'yandex'/(number+'.wav')),'wb') as stream:
            stream.setnchannels(1);stream.setsampwidth(2);stream.setframerate(8000);stream.writeframes(b'\0\0'*8000)
    with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
    config=folder/'navidrome.toml'
    config.write_text('Address="127.0.0.1"\nPort='+str(port)+'\nMusicFolder='+json.dumps(str(library))+
        '\nDataFolder='+json.dumps(str(folder/'state'))+'\nLogLevel="error"\nAutoImportPlaylists=false\n'
        'EnableInsightsCollector=false\nEnableExternalServices=false\n[Scanner]\nSchedule="0"\n')
    profile.NAV='http://127.0.0.1:'+str(port)
    password=secrets.token_urlsafe(30)
    def api(path,payload):
        request=urllib.request.Request(profile.NAV+path,data=json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
        with profile.HTTP.open(request,timeout=10) as response:return json.loads(response.read(65536))
    with (folder/'native.log').open('xb') as log:
        child=subprocess.Popen([str(binary),'--configfile',str(config)],stdin=subprocess.DEVNULL,stdout=log,stderr=log)
        try:
            for _ in range(90):
                if child.poll() is not None:raise RuntimeError('Native fixture stopped before readiness')
                try:
                    with profile.HTTP.open(profile.NAV+'/ping',timeout=1) as response:
                        if response.status==200:break
                except OSError:pass
                time.sleep(.2)
            else:raise RuntimeError('Native readiness timed out')
            api('/auth/createAdmin',{'username':'fixture-owner','password':password})
            auth=api('/auth/login',{'username':'fixture-owner','password':password})
            client=playlists.Native({'uid':auth['id'],'native':auth['token']},threading.Event())
            for _ in range(100):
                found=client.songs(['yandex/1.wav','yandex/2.wav'])
                if len(found)==2:break
                time.sleep(.2)
            else:raise RuntimeError('Native exact relative-path songs were not indexed')
            comment='Imported from Yandex Music; DedSec Music fixture-'+secrets.token_hex(16)
            first=client.create('Source title',comment)
            # Deliberately not lexical;12 entries also expose lexical relation sorting.
            a,b=sorted(found.values());desired=[b,a,b,a,a,b,b,a,b,a,b,a]
            client.replace(first['meta']['id'],desired)
            state=client.state(first['meta']['id'])
            if state!={'meta':first['meta'],'ids':desired}:raise RuntimeError('Native metadata/order/repeats contract differed')
            client.replace(first['meta']['id'],desired)
            if client.state(first['meta']['id'])!=state:raise RuntimeError('Native replace was not idempotent')
            playlists.safe(state,auth['id'],comment,'Source title')
            if client.lookup(comment)!=state:raise RuntimeError('Native private marker recovery differed')
            (folder/'proof.json').write_text(json.dumps({'navVersion':'0.64.2','exactRelativePath':True,'nativeUserJWT':True,
                'privateEditable':True,'relationOrder12':True,'nonLexicalSongIDs':True,'repeatedIDs':True,'replaceIdempotent':True,'metadataRetained':True},indent=2))
            print('PASS native0.64.2: JWT, exact path, private editable,12 ordered/repeated entries, replace twice, marker recovery. Fixture:',folder)
        finally:
            password=''
            if child.poll() is None:
                child.terminate()
                try:child.wait(timeout=10)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=5)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--binary',type=Path,required=True);parser.add_argument('--parent',type=Path,required=True)
    args=parser.parse_args();main(args.binary.resolve(),args.parent.resolve())
