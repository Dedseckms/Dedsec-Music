# SPDX-License-Identifier: GPL-3.0-or-later
"""Fixed-file Linux metrics, bounded history and process names; no process credentials."""
import contextlib
import heapq
import json
import math
import os
from pathlib import Path
import sqlite3
import threading
import time

INTERVAL=5
MAX_POINTS=360


def text(path,cap=65536):
    with Path(path).open('rb') as stream:data=stream.read(cap+1)
    if len(data)>cap:raise ValueError('Metric source exceeds bound')
    return data.decode('utf-8',errors='replace')


class Collector:
    def __init__(self,proc=Path('/proc'),cgroup=Path('/sys/fs/cgroup'),scope=None,clock=time.monotonic):
        self.proc,self.cgroup=Path(proc),Path(cgroup)
        self.scope=scope or ('container' if Path('/.dockerenv').exists() else 'host')
        self.clock=clock;self.previous=None;self.pids={}
        self.ticks=os.sysconf('SC_CLK_TCK') if hasattr(os,'sysconf') else 100
        self.page=os.sysconf('SC_PAGE_SIZE') if hasattr(os,'sysconf') else 4096

    def number(self,name):
        try:return int(text(self.cgroup/name,128).strip())
        except (OSError,ValueError):return None

    def collect(self):
        now=self.clock();cpu=[int(v) for v in text(self.proc/'stat').splitlines()[0].split()[1:9]]
        total,idle=sum(cpu),cpu[3]+cpu[4]
        mem={line.split(':',1)[0]:int(line.split()[1])*1024 for line in text(self.proc/'meminfo').splitlines() if ':' in line and len(line.split())>=2 and line.split()[1].isdigit()}
        load=[float(v) for v in text(self.proc/'loadavg',1024).split()[:3]]
        uptime=float(text(self.proc/'uptime',1024).split()[0]);cpu_count=max(1,os.cpu_count() or 1)
        used=max(0,mem.get('MemTotal',0)-mem.get('MemAvailable',0));available=mem.get('MemAvailable',0);memory_total=mem.get('MemTotal',0)
        swap=max(0,mem.get('SwapTotal',0)-mem.get('SwapFree',0));usage=None;quota=None
        if self.scope=='container':
            limit,current=self.number('memory.max'),self.number('memory.current')
            memory_total,used,available=limit,current,max(0,limit-current) if limit is not None and current is not None else None
            swap=self.number('memory.swap.current')
            try:
                values=text(self.cgroup/'cpu.max',128).split()
                if values[0]!='max':quota=int(values[0])/int(values[1]);cpu_count=max(1,math.ceil(quota))
                usage=int(dict(line.split() for line in text(self.cgroup/'cpu.stat',4096).splitlines())['usage_usec'])
            except (OSError,ValueError,KeyError,ZeroDivisionError):pass
        rx=tx=0
        for line in text(self.proc/'net/dev',65536).splitlines()[2:]:
            name,values=line.split(':',1);name=name.strip();fields=values.split()
            if name=='lo' or (self.scope=='host' and (name.startswith(('veth','docker','br-','tun','tap','wg')))):continue
            rx+=int(fields[0]);tx+=int(fields[8])
        elapsed=now-self.previous['clock'] if self.previous else 0
        percent=rx_rate=tx_rate=None
        if self.previous and elapsed>0:
            delta=total-self.previous['total']
            if delta>0:percent=max(0,min(100,100*(1-(idle-self.previous['idle'])/delta)))
            if usage is not None and self.previous['usage'] is not None and quota:
                percent=max(0,min(100,(usage-self.previous['usage'])/10000/elapsed/quota))
            rx_rate=max(0,(rx-self.previous['rx'])/elapsed);tx_rate=max(0,(tx-self.previous['tx'])/elapsed)
        processes=[];new_pids={};process_count=0;scanned=0;deadline=self.clock()+.75
        for directory in self.proc.iterdir():
            if not directory.name.isdigit():continue
            process_count+=1
            if scanned>=4096 or self.clock()>deadline:continue
            scanned+=1
            try:
                fields=text(directory/'stat',8192).rsplit(') ',1)[1].split()
                ticks=int(fields[11])+int(fields[12]);start=int(fields[19]);pid=int(directory.name)
                name=''.join(c for c in text(directory/'comm',256).strip() if c.isprintable())[:64]
                rss=max(0,int(fields[21]))*self.page;key=(pid,start);old=self.pids.get(key)
                process_cpu=max(0,min(100*cpu_count,(ticks-old)/self.ticks/elapsed*100)) if old is not None and elapsed>0 else None
                new_pids[key]=ticks
                row={'id':str(pid)+'-'+str(start),'name':name,'pid':pid,'cpuPercent':process_cpu,'rssBytes':rss}
                item=(process_cpu or 0,rss,pid,row)
                if len(processes)<128:heapq.heappush(processes,item)
                elif item[:3]>processes[0][:3]:heapq.heapreplace(processes,item)
            except (OSError,ValueError,IndexError):continue
        self.pids=new_pids;self.previous={'clock':now,'total':total,'idle':idle,'usage':usage,'rx':rx,'tx':tx}
        processes=[item[-1] for item in sorted(processes,reverse=True)]
        latest={'cpuPercent':percent,'memoryTotalBytes':memory_total,'memoryUsedBytes':used,'memoryAvailableBytes':available,
            'swapUsedBytes':swap,'load1':load[0],'load5':load[1],'load15':load[2],'rxBytesPerSecond':rx_rate,'txBytesPerSecond':tx_rate,
            'uptimeSeconds':uptime,'cpuCount':cpu_count,'processCount':process_count}
        if self.scope=='container':
            if usage is None or quota is None:latest['cpuPercent']=None
            if self.number('memory.current') is None:
                for key in ('memoryTotalBytes','memoryUsedBytes','memoryAvailableBytes'):latest[key]=None
            for key in ('load1','load5','load15'):latest[key]=None
        at=time.time();latest['at']=at
        return {'sampledAt':at,'scope':self.scope,'cpuCount':cpu_count,'uptimeSeconds':uptime,'processCount':process_count,
                'latest':latest,'processes':processes,'processesTruncated':scanned<process_count}


class Sampler:
    def __init__(self,state,collector=None,start=True):
        self.folder=Path(state)/'system';self.folder.mkdir(mode=0o700,parents=True,exist_ok=True)
        if self.folder.is_symlink() or self.folder.resolve()!=self.folder:raise ValueError('Physical metric state required')
        self.path=self.folder/'history.sqlite';self.collector=collector or Collector();self.stop=threading.Event();self.lock=threading.Lock();self.latest=None;self.batch=[]
        if self.path.is_symlink():raise ValueError('Physical metric database required')
        with self.db() as db:db.execute('CREATE TABLE IF NOT EXISTS samples(at REAL PRIMARY KEY,value TEXT NOT NULL)')
        self.path.chmod(0o600);self.thread=None
        if start:
            self.thread=threading.Thread(target=self.loop,name='system-metrics',daemon=True);self.thread.start()

    @contextlib.contextmanager
    def db(self):
        db=sqlite3.connect(self.path,timeout=.5)
        try:
            with db:yield db
        finally:db.close()

    def sample(self):
        value=self.collector.collect();latest=value['latest']
        history={'at':value['sampledAt'],**{key:latest.get(key) for key in ('cpuPercent','memoryTotalBytes','memoryUsedBytes','memoryAvailableBytes','swapUsedBytes','load1','load5','load15','rxBytesPerSecond','txBytesPerSecond')},
            'processes':[{key:row[key] for key in ('id','cpuPercent','rssBytes')} for row in value['processes'][:8]]}
        with self.lock:
            self.latest=value;self.batch.append(history);self.batch=self.batch[-12:]
            if len(self.batch)>=12:self.flush_locked()

    def flush_locked(self):
        with self.db() as db:
            db.executemany('INSERT OR REPLACE INTO samples VALUES(?,?)',[(row['at'],json.dumps(row,separators=(',',':'))) for row in self.batch])
            db.execute('DELETE FROM samples WHERE at<?',(time.time()-86400,))
        self.batch.clear()

    def loop(self):
        while not self.stop.is_set():
            try:self.sample()
            except (OSError,ValueError,sqlite3.Error):pass
            self.stop.wait(INTERVAL)

    def view(self,window=3600,points=240):
        if type(window) is not int or not 60<=window<=86400 or type(points) is not int or not 1<=points<=MAX_POINTS:raise ValueError('Metric range exceeds limit')
        start=time.time()-window;width=max(INTERVAL,window/points)
        with self.lock:
            latest=self.latest.copy() if self.latest else {'sampledAt':None,'scope':self.collector.scope,'latest':None,'processes':[]}
            with self.db() as db:
                rows=[json.loads(r[0]) for r in db.execute('SELECT value FROM samples WHERE at IN (SELECT max(at) FROM samples WHERE at>=? GROUP BY CAST((at-?)/? AS INTEGER)) ORDER BY at',(start,start,width))]
            rows.extend(row for row in self.batch if row['at']>=start)
        buckets={int((row['at']-start)/width):row for row in rows}
        latest.update(intervalSeconds=INTERVAL,history=[buckets[k] for k in sorted(buckets)][-points:])
        return latest

    def close(self):
        self.stop.set()
        if self.thread:self.thread.join(timeout=6)
        with self.lock:self.flush_locked()
