# SPDX-License-Identifier: GPL-3.0-or-later
import contextlib
import io
import json
import os
from pathlib import Path
import sqlite3
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'api'))
import admin_api
import profile_api as profile
import storage_jobs as storage
import system_admin
from system_stats import Collector,Sampler


class MetricsTests(unittest.TestCase):
    def fixture(self,root):
        proc=root/'proc';proc.mkdir();(proc/'net').mkdir();cgroup=root/'cg';cgroup.mkdir()
        (proc/'stat').write_text('cpu 100 0 100 800 0 0 0 0\n')
        (proc/'meminfo').write_text('MemTotal: 1000 kB\nMemAvailable: 600 kB\nSwapTotal: 100 kB\nSwapFree: 90 kB\n')
        (proc/'loadavg').write_text('0.1 0.2 0.3 1/10 1\n');(proc/'uptime').write_text('1000 100\n')
        (proc/'net/dev').write_text('header\nheader\neth0: 100 0 0 0 0 0 0 0 200 0 0 0 0 0 0 0\nlo: 999 0 0 0 0 0 0 0 999 0 0 0 0 0 0 0\n')
        return proc,cgroup

    def process(self,proc,pid,ticks=10,start=100):
        p=proc/str(pid);p.mkdir(exist_ok=True);fields=['0']*22;fields[11]=str(ticks);fields[19]=str(start);fields[21]='20'
        (p/'stat').write_text(str(pid)+' (test process) '+' '.join(fields));(p/'comm').write_text('safe-process\n')
        (p/'cmdline').write_text('SECRET_SHOULD_NEVER_BE_READ');(p/'environ').write_text('SECRET')

    def test_cpu_network_delta_process_identity_and_no_commandline(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);proc,cg=self.fixture(root);self.process(proc,1)
            clock=[1.0];collector=Collector(proc,cg,'host',clock=lambda:clock[0]);first=collector.collect()
            self.assertIsNone(first['latest']['cpuPercent'])
            (proc/'stat').write_text('cpu 200 0 100 900 0 0 0 0\n');self.process(proc,1,20)
            (proc/'net/dev').write_text('header\nheader\neth0: 200 0 0 0 0 0 0 0 400 0 0 0 0 0 0 0\n')
            clock[0]=6.0;second=collector.collect()
            self.assertEqual(second['latest']['cpuPercent'],50)
            self.assertEqual(second['latest']['rxBytesPerSecond'],20)
            self.assertEqual(second['latest']['processCount'],1)
            self.assertNotIn('SECRET',json.dumps(second));self.assertEqual(second['processes'][0]['id'],'1-100')
            self.process(proc,1,2,200);clock[0]=11
            self.assertIsNone(collector.collect()['processes'][0]['cpuPercent'])

    def test_container_metrics_never_fall_back_to_host_cpu_load(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);proc,cg=self.fixture(root);collector=Collector(proc,cg,'container')
            value=collector.collect();self.assertIsNone(value['latest']['load1']);self.assertIsNone(value['latest']['memoryUsedBytes'])
            (cg/'memory.max').write_text('2000');(cg/'memory.current').write_text('300')
            (cg/'cpu.max').write_text('50000 100000');(cg/'cpu.stat').write_text('usage_usec 1000\n')
            value=collector.collect();self.assertEqual(value['latest']['memoryAvailableBytes'],1700);self.assertEqual(value['latest']['cpuCount'],1)

    def test_unlimited_container_limits_do_not_expose_host_usage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);proc,cg=self.fixture(root);clock=[1.0]
            (cg/'memory.max').write_text('max');(cg/'memory.current').write_text('300')
            (cg/'cpu.max').write_text('max 100000');(cg/'cpu.stat').write_text('usage_usec 1000\n')
            collector=Collector(proc,cg,'container',clock=lambda:clock[0]);collector.collect()
            (cg/'cpu.stat').write_text('usage_usec 1000000\n');clock[0]=6
            value=collector.collect()['latest']
            self.assertEqual(value['memoryUsedBytes'],300)
            self.assertIsNone(value['memoryTotalBytes']);self.assertIsNone(value['memoryAvailableBytes'])
            self.assertIsNone(value['cpuPercent']);self.assertIsNone(value['load1'])

    def test_top_process_limit_history_bounds_and_unix_seconds(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);proc,cg=self.fixture(root)
            for pid in range(1,150):self.process(proc,pid)
            value=Collector(proc,cg,'host').collect();self.assertEqual(len(value['processes']),128);self.assertEqual(value['latest']['processCount'],149)
            fake=types.SimpleNamespace(scope='host',collect=lambda:value)
            sampler=Sampler(root/'state',fake,start=False)
            for _ in range(13):sampler.sample()
            result=sampler.view(60,1);self.assertLessEqual(len(result['history']),1)
            self.assertIsInstance(result['sampledAt'],float);self.assertLess(result['sampledAt'],10**11)
            self.assertLessEqual(len(result['history'][0]['processes']),8)
            with self.assertRaises(ValueError):sampler.view(86401,360)
            with self.assertRaises(ValueError):sampler.view(3600,361)
            sampler.close()


class StorageTests(unittest.TestCase):
    def fixture(self,root):
        library=root/'library';library.mkdir();(library/'tracks').mkdir();(library/'tracks/a.mp3').write_bytes(b'audio-content')
        target=root/'target';target.mkdir();state=root/'state'
        manager=storage.Manager(state,library,[{'id':'extra','root':str(target),'expectedDevice':target.stat().st_dev}],start=False)
        # Two temp directories share a filesystem. Only the fixture simulates an approved extra volume.
        status=manager.volume_status
        manager.volume_status=lambda key:({**status(key),'eligibleTarget':True,'reason':None,'availableBytes':2**50} if key=='extra' else status(key))
        return manager,library,target

    def plan_job(self,m):
        plan=m.prepare('tracks','extra');job=m.execute('admin',plan['id'],plan['planHash']);return plan,job

    def test_default_same_filesystem_ineligible_and_unknown_paths_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,library,target=self.fixture(Path(tmp))
            self.assertEqual(storage.Manager.volume_status(m,'extra')['reason'],'SAME_FILESYSTEM')
            self.assertFalse(m.volume_status('primary')['eligibleTarget'])
            with self.assertRaises(storage.StorageError):m.prepare('../../outside','extra')
            with self.assertRaises(storage.StorageError):m.prepare('tracks','/arbitrary')

    def test_copy_checksums_preserves_source_logical_path_and_waits_for_operator(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m);before=(lib/'tracks/a.mp3').stat()
            m.run(job['id']);value=m.view(job['id'])
            self.assertEqual(value['state'],'awaiting_activation');self.assertEqual(value['filesDone'],1);self.assertEqual(value['bytesCopied'],13)
            self.assertEqual((target/'dedsec-music-copies'/job['id']/'data/tracks/a.mp3').read_bytes(),b'audio-content')
            self.assertEqual(storage.stamp((lib/'tracks/a.mp3').stat()),storage.stamp(before))
            self.assertTrue(plan['requiresOperatorActivation']);self.assertNotIn('completed',value['state'])

    def test_stale_hash_expired_plan_and_changed_source_never_publish(self):
        for case in ('hash','expired','changed'):
            with self.subTest(case=case),tempfile.TemporaryDirectory() as tmp:
                m,lib,target=self.fixture(Path(tmp));plan=m.prepare('tracks','extra')
                if case=='expired':
                    with m.db() as d:d.execute('UPDATE plans SET expires=0')
                if case in ('hash','expired'):
                    with self.assertRaises(storage.StorageError):m.execute('a',plan['id'],'0'*64 if case=='hash' else plan['planHash'])
                else:
                    job=m.execute('a',plan['id'],plan['planHash']);(lib/'tracks/a.mp3').write_bytes(b'changed');m.run(job['id'])
                    self.assertEqual(m.view(job['id'])['errorCode'],'SOURCE_CHANGED')
                self.assertFalse((target/'dedsec-music-copies').exists())

    def test_partial_cancel_resume_and_restart_recovery_are_idempotent(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m);copy=m.copy_file
            def interrupted(*args):
                destination=args[-1];part=destination/'staging'/(storage.hashlib.sha256(b'a.mp3').hexdigest()+'.part')
                m.safe_directory(part.parent,destination);part.write_bytes(b'audio');part.chmod(0o600)
                m.action(job['id'],'cancel');raise storage.StorageError(409,'CANCELLED')
            m.copy_file=interrupted;m.run(job['id']);self.assertEqual(m.view(job['id'])['state'],'cancelled')
            m.copy_file=copy;m.action(job['id'],'resume');m.run(job['id']);self.assertEqual(m.view(job['id'])['state'],'awaiting_activation')
            self.assertEqual(m.view(job['id'])['filesDone'],1)
            # Already-published retry verifies the digest; it cannot append duplicate data.
            with m.db() as d:d.execute("UPDATE jobs SET state='queued'")
            m.run(job['id']);self.assertEqual(m.view(job['id'])['bytesCopied'],13)

    def test_long_concurrent_plan_snapshot_does_not_lock_or_kill_copy_worker(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m)
            entered=threading.Event();release=threading.Event();original=m.snapshot;results=[];errors=[]
            def snapshot(root,deadline):
                if threading.current_thread().name=='slow-plan':
                    entered.set()
                    if not release.wait(5):raise AssertionError('Test release deadline')
                yield from original(root,deadline)
            m.snapshot=snapshot
            def planning():
                try:results.append(m.prepare('tracks','extra'))
                except BaseException as error:errors.append(error)
            planner=threading.Thread(target=planning,name='slow-plan');planner.start()
            self.assertTrue(entered.wait(2))
            m.thread=threading.Thread(target=m.loop,daemon=True);m.thread.start()
            try:
                # Longer than the historical .5s SQLite timeout: copy must finish while scan remains blocked.
                deadline=time.monotonic()+2
                while time.monotonic()<deadline and m.view(job['id'])['state']!='awaiting_activation':time.sleep(.02)
                self.assertEqual(m.view(job['id'])['state'],'awaiting_activation')
                time.sleep(.55);self.assertTrue(m.thread.is_alive())
            finally:release.set();planner.join(3);m.close()
            self.assertFalse(errors);self.assertEqual(len(results),1)

    def test_worker_survives_failed_error_write_and_pauses_orphan_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m);original=m.run;calls=[]
            def interrupted(key):
                calls.append(key);m.update(key,state='running');raise sqlite3.OperationalError('busy error-report write')
            m.run=interrupted;m.thread=threading.Thread(target=m.loop,daemon=True);m.thread.start()
            try:
                deadline=time.monotonic()+3
                while time.monotonic()<deadline and m.view(job['id'])['state']!='paused':time.sleep(.02)
                self.assertTrue(m.thread.is_alive());self.assertEqual(m.view(job['id'])['state'],'paused')
                self.assertEqual(m.view(job['id'])['errorCode'],'IO_ERROR');self.assertEqual(len(calls),1)
            finally:m.close()

    def test_conflicting_destination_low_space_and_single_job(self):
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m)
            second=m.prepare('tracks','extra')
            with self.assertRaises(storage.StorageError):m.execute('a',second['id'],second['planHash'])
            final=target/'dedsec-music-copies'/job['id']/'data/tracks/a.mp3';final.parent.mkdir(parents=True);final.parent.chmod(0o700);final.write_bytes(b'foreign')
            m.run(job['id']);self.assertEqual(m.view(job['id'])['errorCode'],'TARGET_CONFLICT');self.assertEqual(final.read_bytes(),b'foreign')
        with tempfile.TemporaryDirectory() as tmp:
            m,lib,target=self.fixture(Path(tmp));plan,job=self.plan_job(m)
            with patch.object(storage.shutil,'disk_usage',return_value=types.SimpleNamespace(free=0,total=1,used=1)):m.run(job['id'])
            self.assertEqual(m.view(job['id'])['errorCode'],'NO_SPACE');self.assertEqual((lib/'tracks/a.mp3').read_bytes(),b'audio-content')

    def test_symlink_source_or_destination_never_followed(self):
        if os.name=='nt':self.skipTest('POSIX symlink permissions')
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);m,lib,target=self.fixture(root);outside=root/'outside';outside.write_bytes(b'secret')
            (lib/'tracks/link.mp3').symlink_to(outside)
            with self.assertRaises(storage.StorageError):m.prepare('tracks','extra')
            (lib/'tracks/link.mp3').unlink();plan,job=self.plan_job(m)
            destination=target/'dedsec-music-copies'/job['id'];destination.mkdir(parents=True);destination.chmod(0o700)
            (destination/'data').symlink_to(root)
            m.run(job['id']);self.assertEqual(m.view(job['id'])['errorCode'],'UNSAFE_PATH');self.assertEqual(outside.read_bytes(),b'secret')


class SystemAuthTests(unittest.TestCase):
    def request(self,path,command='GET',data=None,admin=True):
        handler=types.SimpleNamespace(path='/dedsec/profile/v1/admin/'+path,command=command,headers={},rfile=io.BytesIO(json.dumps(data).encode() if data is not None else b''))
        result=[]
        with patch.object(profile,'authenticate',return_value={'id':'u','token':'not-logged'}),patch.object(profile,'native',return_value=({'id':'u','isAdmin':admin},{})),patch.object(profile,'send',side_effect=lambda h,s,v:result.append((s,v))):
            status=admin_api.dispatch(handler,lambda f,h:iter([f.read()]))
        return status,result[0][1]

    def test_fresh_admin_required_for_every_read_and_mutation(self):
        for path,method in [('system','GET'),('storage','GET'),('storage/plans','POST'),('storage/moves','POST')]:
            self.assertEqual(self.request(path,method,{},admin=False)[0],403)
        with patch.object(profile,'authenticate',side_effect=profile.ProfileError(401)):
            handler=types.SimpleNamespace(path='/dedsec/profile/v1/admin/system',headers={})
            with patch.object(profile,'send'):self.assertEqual(admin_api.dispatch(handler,None),401)

    def test_direct_dto_and_invalid_ranges_body_bound(self):
        fake=types.SimpleNamespace(metrics=types.SimpleNamespace(view=lambda w,p:{'scope':'host','latest':None,'processes':[],'history':[]}),storage=types.SimpleNamespace(inventory=lambda:{'volumes':[],'shards':[],'activeJob':None}))
        with patch.object(system_admin,'SERVICE',fake):
            status,value=self.request('storage');self.assertEqual(status,200);self.assertIn('volumes',value);self.assertNotIn('storage',value)
            self.assertEqual(self.request('system?points=2&points=3')[0],400)
            self.assertEqual(self.request('storage/plans','POST',{'sourceShardId':'x'*5000,'targetVolumeId':'y'})[0],413)


if __name__=='__main__':unittest.main()
