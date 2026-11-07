#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Navidrome-backed self profiles and validated, append-only music uploads."""

import collections
import contextlib
import http.server
import json
import os
from pathlib import Path
import shutil
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid

import audio
import profile_api as profile
import yandex_import

STORAGE = Path(os.environ.get('STORAGE_ROOT', '/storage')).resolve()
LIBRARY = STORAGE / 'library'
STAGING = STORAGE / 'staging'
LOGIN_LOCK = threading.Lock()
LOGIN_ATTEMPTS = collections.deque()
IMPORTER = None


def login_allowed(now=None):
    now = time.monotonic() if now is None else now
    with LOGIN_LOCK:
        while LOGIN_ATTEMPTS and LOGIN_ATTEMPTS[0] <= now - 60:
            LOGIN_ATTEMPTS.popleft()
        if len(LOGIN_ATTEMPTS) >= 20:
            return False
        LOGIN_ATTEMPTS.append(now)
        return True


def login(query, headers):
    values = urllib.parse.parse_qs(query, keep_blank_values=True, strict_parsing=True)
    if set(values) != {'username'} or len(values['username']) != 1:
        raise profile.ProfileError(400)
    username = values['username'][0]
    encoded = headers.get('X-Password', '')
    if (not 1 <= len(username.encode()) <= 128 or not encoded or len(encoded) > 1024
            or len(headers.get_all('X-Password', [])) != 1):
        raise profile.ProfileError(400)
    try:
        password = urllib.parse.unquote(encoded, encoding='utf-8', errors='strict')
    except UnicodeError:
        raise profile.ProfileError(400) from None
    if not 1 <= len(password.encode()) <= 256:
        raise profile.ProfileError(400)
    if not login_allowed():
        raise profile.ProfileError(429)
    request = urllib.request.Request(profile.NAV + '/auth/login',
        data=json.dumps({'username': username, 'password': password}).encode(),
        headers={'Content-Type': 'application/json'}, method='POST')
    try:
        with profile.HTTP.open(request, timeout=8) as response:
            raw = response.read(65537)
            if len(raw) > 65536:
                raise profile.ProfileError(502)
            result = json.loads(raw)
    except urllib.error.HTTPError as error:
        raise profile.ProfileError(429 if error.code == 429 else 401 if error.code in (401, 403) else 502) from None
    except (OSError, ValueError):
        raise profile.ProfileError(502) from None
    password = ''
    token = result.get('token')
    if not isinstance(token, str) or len(token) > 8192 or len(token.split('.')) != 3:
        raise profile.ProfileError(502)
    # The same native verification is required for uploader and profile requests.
    from email.message import Message
    auth = Message(); auth['Authorization'] = 'Bearer ' + token
    profile.authenticate(auth)
    return token.encode()


def upload(identity, query, chunks):
    name = audio.filename(query)
    if not audio.UPLOAD.acquire(blocking=False):
        raise audio.Rejected(429)
    try:
        # Stable user UUID plus a fresh upload UUID; original basename stays searchable.
        user_id = str(uuid.uuid5(uuid.NAMESPACE_URL, identity['id']))
        folder = LIBRARY / 'uploads' / user_id
        for directory in (LIBRARY / 'uploads', folder):
            directory.mkdir(mode=0o700, exist_ok=True)
            fd = audio.safe_directory(directory); os.close(fd)
        def validate(path):
            duration = audio.validate(path)
            current, _ = profile.native(identity['token'], '/api/user/' + identity['id'])
            if current.get('id') != identity['id']:
                raise profile.ProfileError(401)
            return duration
        size, seconds = audio.receive(chunks, STAGING, folder, name, validator=validate)
        return {'status': 'accepted', 'name': name, 'bytes': size, 'duration': seconds}
    finally:
        audio.UPLOAD.release()


class Handler(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def setup(self):
        super().setup()
        self.connection.settimeout(15)

    def log_message(self, *_):
        pass

    def result(self, status, value=None, raw=False):
        data = value if raw else json.dumps(value or {}, separators=(',', ':')).encode()
        self.close_connection = True
        self.send_response(status)
        self.send_header('Content-Type', 'text/plain' if raw else 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'private, no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Connection', 'close')
        self.end_headers()
        if self.command != 'HEAD':
            self.wfile.write(data)

    def dispatch(self):
        status = 500
        try:
            parsed = urllib.parse.urlsplit(self.path)
            if parsed.path.startswith('/dedsec/import/v1/'):
                if IMPORTER is None:
                    status=503;self.result(status,{'error':{'code':'INTERNAL_ERROR','message':'Import unavailable'}});return
                status=yandex_import.dispatch(self,IMPORTER)
                if status is None:status=404;self.result(status,{'error':{'code':'NOT_FOUND','message':'Not found'}})
                return
            if parsed.path.startswith(profile.PREFIX + '/'):
                status = profile.dispatch(self, audio.body_chunks)
                return
            if self.command == 'GET' and self.path == '/health':
                status = 200; self.result(status, {'status': 'ok'}); return
            if self.command == 'POST' and parsed.path == '/api/auth/login':
                if self.headers.get('Transfer-Encoding') or self.headers.get('Content-Length', '0') != '0':
                    raise profile.ProfileError(400)
                status = 200; self.result(status, login(parsed.query, self.headers), raw=True); return
            identity = profile.authenticate(self.headers)
            if self.command == 'GET' and self.path == '/api/settings/sources':
                disk = shutil.disk_usage(STORAGE)
                status = 200
                self.result(status, {'Uploads': {'usedAlt': disk.total-disk.free, 'total': disk.total,
                    'free': disk.free, 'reserve': audio.RESERVE}})
            elif self.command == 'POST' and parsed.path == '/api/resources':
                if self.headers.get('Content-Length') and int(self.headers['Content-Length']) > audio.MAX_BYTES:
                    raise audio.Rejected(413)
                result = upload(identity, parsed.query, audio.body_chunks(self.rfile, self.headers))
                status = 200; self.result(status, result)
            else:
                raise profile.ProfileError(405)
        except (profile.ProfileError, audio.Rejected) as error:
            status = error.status; self.result(status, {'error': 'request_rejected'})
        except (OSError, ValueError, TypeError, KeyError):
            status = 503
            with contextlib.suppress(OSError): self.result(status, {'error': 'temporarily_unavailable'})
        finally:
            # Never log paths, query strings, headers, account identifiers or exceptions.
            print('music_api status=' + str(status), flush=True)

    do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = do_OPTIONS = dispatch


class Server(http.server.ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, *args):
        self.slots = threading.BoundedSemaphore(8)
        super().__init__(*args)
    def process_request(self, request, address):
        if not self.slots.acquire(blocking=False):
            request.close(); return
        try: super().process_request(request, address)
        except BaseException:
            self.slots.release(); raise
    def process_request_thread(self, request, address):
        try: super().process_request_thread(request, address)
        finally: self.slots.release()
    def handle_error(self, *_):
        print('music_api transport_error', flush=True)


if __name__ == '__main__':
    os.umask(0o077)
    for folder in (STORAGE, LIBRARY, STAGING, profile.BASE):
        folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd = audio.safe_directory(folder); os.close(fd)
    if STAGING.stat().st_dev != LIBRARY.stat().st_dev:
        raise RuntimeError('Staging/library must be on the same filesystem')
    IMPORTER=yandex_import.Manager(profile.BASE,STORAGE)
    try:Server(('0.0.0.0', 8080), Handler).serve_forever(poll_interval=1)
    finally:IMPORTER.close()
