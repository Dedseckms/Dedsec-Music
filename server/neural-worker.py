#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Single CPU offline descriptor worker; no model generation or speech transcription."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import stat
import time
import urllib.parse
import urllib.request

MODEL_SHA='10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de'
MODEL='yamnet521-'+MODEL_SHA


def constrain():
    import resource
    os.environ.update(OPENBLAS_NUM_THREADS='1',OMP_NUM_THREADS='1',TF_NUM_INTRAOP_THREADS='1',TF_NUM_INTEROP_THREADS='1')
    os.nice(19);os.sched_setaffinity(0,{max(os.sched_getaffinity(0))})
    for kind,limit in ((resource.RLIMIT_AS,1024*2**20),(resource.RLIMIT_NOFILE,64),(resource.RLIMIT_CORE,0)):
        resource.setrlimit(kind,(limit,limit))


class Model:
    def __init__(self,path):
        if path.is_symlink() or path.stat().st_size>5*2**20 or hashlib.sha256(path.read_bytes()).hexdigest()!=MODEL_SHA:raise ValueError('Pinned YAMNet required')
        import numpy as np
        from ai_edge_litert.interpreter import Interpreter
        self.np=np;self.engine=Interpreter(model_path=str(path),num_threads=1)
        self.engine.allocate_tensors();self.input=self.engine.get_input_details()[0];self.output=self.engine.get_output_details()[0]
        if self.input['shape'].tolist()!=[15600] or self.output['shape'].tolist()!=[1,521]:raise ValueError('Exact521 model required')

    def scores(self,raw):
        np=self.np
        if not 15600*2<=len(raw)<=15600*4*2 or len(raw)%2:raise ValueError('PCM limit')
        samples=np.frombuffer(raw,dtype='<i2').astype(np.float32)/32768
        results=[]
        for offset in range(0,len(samples)-15600+1,15600):
            self.engine.set_tensor(self.input['index'],samples[offset:offset+15600]);self.engine.invoke()
            results.append(self.engine.get_tensor(self.output['index'])[0])
        value=np.mean(results,axis=0)
        if not np.all(np.isfinite(value)) or np.any(value<0) or np.any(value>1):raise ValueError('Invalid inference')
        return [round(float(x),6) for x in value]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):raise ValueError('Redirect rejected')


def token(path):
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW)
    try:
        info=os.fstat(fd)
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_uid!=os.getuid() or stat.S_IMODE(info.st_mode)!=0o600:raise ValueError('Private worker token required')
        value=os.read(fd,129).decode().strip()
    finally:os.close(fd)
    if len(value)!=64 or any(c not in '0123456789abcdef' for c in value):raise ValueError('Worker token format')
    return value


def main(args):
    constrain();model=Model(args.model)
    if args.smoke:
        import resource
        import numpy as np
        started=time.monotonic();t=np.arange(15600*4)/16000
        raw=(12000*np.sin(440*2*np.pi*t)).astype('<i2').tobytes();scores=model.scores(raw)
        print(json.dumps({'model':MODEL,'seconds':round(time.monotonic()-started,4),'maxRSSKiB':resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,'dimensions':len(scores),'threads':len(os.listdir('/proc/self/task'))}));return
    parsed=urllib.parse.urlsplit(args.origin)
    if parsed.scheme!='https' or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('','/'):raise ValueError('HTTPS origin required')
    http=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def post(route,data):
        request=urllib.request.Request(args.origin.rstrip('/')+'/dedsec/profile/v1/radio/worker/'+route,data=json.dumps(data).encode(),
            headers={'Authorization':'Bearer '+token(args.token),'Content-Type':'application/json'},method='POST')
        with http.open(request,timeout=20) as incoming:
            raw=incoming.read(200000)
            if len(raw)>=200000:raise ValueError('Response limit')
            return json.loads(raw)
    while not args.stop.exists():
        started=time.monotonic()
        try:
            job=post('lease',{})['job']
            if job is not None:
                if job['model']!=MODEL or job['sampleRate']!=16000:raise ValueError('Unsupported job')
                raw=base64.b64decode(job['pcm'],validate=True)
                scores=model.scores(raw)
                if not args.stop.exists():post('result',{'lease':job['lease'],'model':MODEL,'scores':scores})
        except Exception as error:
            # No URLs, source IDs, credentials, audio or response bodies in logs.
            print('Worker retry:',type(error).__name__,flush=True)
        if args.once:return
        # <=10% wall duty, minimum30s; stop/cancel remains responsive.
        deadline=time.monotonic()+max(30,(time.monotonic()-started)*9)
        while time.monotonic()<deadline and not args.stop.exists():time.sleep(min(1,deadline-time.monotonic()))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model',type=Path,required=True);parser.add_argument('--origin',default='https://music.dedseckms.ru')
    parser.add_argument('--token',type=Path,default=Path(__file__).resolve().parent/'worker.token')
    parser.add_argument('--stop',type=Path,default=Path(__file__).resolve().parent/'worker.stop')
    parser.add_argument('--smoke',action='store_true');parser.add_argument('--once',action='store_true')
    main(parser.parse_args())
