# SPDX-License-Identifier: GPL-3.0-or-later
"""Admin-only metrics and registered-volume copy API; no privileged operations."""
import json
import re
import sqlite3
import urllib.parse

import profile_api as profile
from system_stats import Sampler
from storage_jobs import Manager, StorageError

PREFIX=profile.PREFIX+'/admin/'
SERVICE=None


class Service:
    def __init__(self,state,library,volumes=None,slot=None,start=True,collector=None,rate=4*2**20,nav_database=None,library_ids=('1',)):
        self.metrics=Sampler(state,collector,start=start)
        try:self.storage=Manager(state,library,volumes,slot,rate,start=start,nav_database=nav_database,library_ids=library_ids)
        except BaseException:self.metrics.close();raise

    def close(self):
        self.storage.close();self.metrics.close()


def configure(state,library,**kwargs):
    global SERVICE
    SERVICE=Service(state,library,**kwargs)
    return SERVICE


def owned_route(path):
    return path==PREFIX+'system' or path==PREFIX+'storage' or path.startswith(PREFIX+'storage/')


def body(handler,chunks):
    raw=bytearray()
    for block in chunks(handler.rfile,handler.headers):
        raw.extend(block)
        if len(raw)>4096:raise StorageError(413,'INVALID_REQUEST')
    try:value=json.loads(raw)
    except ValueError:raise StorageError(400,'INVALID_REQUEST') from None
    if not isinstance(value,dict):raise StorageError(400,'INVALID_REQUEST')
    return value


def dispatch_authorized(handler,chunks,identity):
    """Caller has just verified the native current isAdmin role (never cached)."""
    parsed=urllib.parse.urlsplit(handler.path)
    try:
        if SERVICE is None:raise StorageError(503,'SYSTEM_UNAVAILABLE')
        try:query=urllib.parse.parse_qs(parsed.query,strict_parsing=True)
        except ValueError:raise StorageError(400,'INVALID_REQUEST') from None
        route=parsed.path[len(PREFIX):]
        if handler.command=='GET' and route=='system':
            if set(query)-{'windowSeconds','points'} or any(len(v)!=1 for v in query.values()):raise StorageError(400,'INVALID_REQUEST')
            try:window,points=int(query.get('windowSeconds',['3600'])[0]),int(query.get('points',['240'])[0])
            except ValueError:raise StorageError(400,'INVALID_REQUEST') from None
            try:value=SERVICE.metrics.view(window,points)
            except ValueError:raise StorageError(400,'INVALID_REQUEST') from None
            if value['latest'] is None:raise StorageError(503,'METRICS_WARMING_UP')
            value['capabilities']={'hostMetrics':value['scope']=='host','processMetrics':True,'storageCopy':True,'automaticActivation':False}
        elif handler.command=='GET' and route=='storage' and not query:value=SERVICE.storage.inventory()
        elif handler.command=='GET' and re.fullmatch(r'storage/moves/[a-f0-9-]{36}',route) and not query:
            value=SERVICE.storage.view(route.split('/')[2])
        elif handler.command=='POST' and not query:
            data=body(handler,chunks)
            if route=='storage/plans' and set(data)=={'sourceShardId','targetVolumeId'} and all(isinstance(v,str) for v in data.values()):
                value=SERVICE.storage.prepare(data['sourceShardId'],data['targetVolumeId'])
            elif route=='storage/moves' and set(data)=={'planId','planHash'} and isinstance(data['planId'],str) and re.fullmatch(r'[a-f0-9-]{36}',data['planId']) and isinstance(data['planHash'],str) and re.fullmatch(r'[a-f0-9]{64}',data['planHash']):
                value=SERVICE.storage.execute(identity['id'],data['planId'],data['planHash'])
            elif re.fullmatch(r'storage/moves/[a-f0-9-]{36}/(cancel|resume)',route) and not data:
                parts=route.split('/');value=SERVICE.storage.action(parts[2],parts[3])
            else:raise StorageError(400,'INVALID_REQUEST')
        else:raise StorageError(405,'METHOD_NOT_ALLOWED')
        if len(json.dumps(value,separators=(',',':')).encode())>512*1024:raise StorageError(503,'RESPONSE_LIMIT')
        profile.send(handler,200,value);return 200
    except StorageError as error:
        profile.send(handler,error.status,{'error':{'code':error.code}});return error.status
    except (OSError,sqlite3.Error,ValueError,TypeError,KeyError):
        profile.send(handler,503,{'error':{'code':'SYSTEM_UNAVAILABLE'}});return 503
