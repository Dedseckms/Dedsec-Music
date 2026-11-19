# SPDX-License-Identifier: GPL-3.0-or-later
"""Same-origin native-JWT upload and lazy lyrics capabilities."""
import contextlib
import json
import os
import re
import sqlite3
import urllib.parse
import uuid

import admin_api
import audio
import profile_api as profile

UPLOAD=None
LYRICS=None


def guard(identity):
    if admin_api.SERVICE and admin_api.SERVICE.native_blocked(identity['id'],identity.get('username','')):
        raise profile.ProfileError(401)


def native_upload(identity,query,chunks,library,staging,validator=None):
    """One common publication path for legacy API and same-origin requests."""
    if admin_api.SERVICE:admin_api.SERVICE.identity(identity)
    name=audio.filename(query)
    if not audio.UPLOAD.acquire(blocking=False):raise audio.Rejected(429)
    try:
        folder=library/'uploads'/str(uuid.uuid5(uuid.NAMESPACE_URL,identity['id']))
        for directory in (library/'uploads',folder):
            directory.mkdir(mode=0o700,exist_ok=True);fd=audio.safe_directory(directory);os.close(fd)
        def validate(path):
            seconds=(validator or audio.validate)(path)
            if admin_api.SERVICE:admin_api.SERVICE.identity(identity)
            current,_=profile.native(identity['token'],'/api/user/'+identity['id'])
            if current.get('id')!=identity['id']:raise profile.ProfileError(401)
            return seconds
        publication=uuid.uuid4().hex
        size,seconds=audio.receive(chunks,staging,folder,name,validator=validate,identifier=publication)
        if admin_api.SERVICE:admin_api.SERVICE.record(identity,folder/publication/name,name,size)
        return {'status':'accepted','name':name,'bytes':size,'duration':seconds}
    finally:audio.UPLOAD.release()


def configure(upload,lyrics):
    global UPLOAD,LYRICS
    UPLOAD,LYRICS=upload,lyrics


def owns(path):
    return path in (profile.PREFIX+'/capabilities',profile.PREFIX+'/uploads') or path.startswith(profile.PREFIX+'/lyrics/')


def dispatch(handler,chunks):
    parsed=urllib.parse.urlsplit(handler.path)
    try:
        identity=profile.authenticate(handler.headers);guard(identity)
        if UPLOAD is None or LYRICS is None:raise profile.ProfileError(503)
        if handler.command=='GET' and parsed.path==profile.PREFIX+'/capabilities' and not parsed.query:
            result={'schema':1,'uploads':{'mode':'navidrome','path':profile.PREFIX+'/uploads'},'lyrics':True}
        elif handler.command=='POST' and parsed.path==profile.PREFIX+'/uploads':
            try:values=urllib.parse.parse_qs(parsed.query,keep_blank_values=True,strict_parsing=True)
            except ValueError:raise profile.ProfileError(400) from None
            if set(values)!={'path'} or len(values['path'])!=1:raise profile.ProfileError(400)
            query=urllib.parse.urlencode({'source':'Uploads','path':values['path'][0],'override':'false'})
            audio.filename(query)
            if handler.headers.get('Content-Length') and int(handler.headers['Content-Length'])>audio.MAX_BYTES:raise audio.Rejected(413)
            result=UPLOAD(identity,query,chunks(handler.rfile,handler.headers))
        elif handler.command=='GET' and not parsed.query and re.fullmatch(re.escape(profile.PREFIX)+r'/lyrics/[A-Za-z0-9_-]{1,160}',parsed.path):
            result=LYRICS.get(identity,parsed.path.rsplit('/',1)[1])
        else:raise profile.ProfileError(400 if parsed.query else 405)
        profile.send(handler,200,result);return 200
    except (profile.ProfileError,audio.Rejected,admin_api.AdminError) as error:
        profile.send(handler,error.status,{'error':'media_request_rejected'});return error.status
    except (OSError,ValueError,TypeError,KeyError,sqlite3.Error):
        profile.send(handler,503,{'error':'media_unavailable'});return 503
