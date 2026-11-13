# SPDX-License-Identifier: GPL-3.0-or-later
"""Native-administrator account operations; private provenance, never music deletion."""
import contextlib
import datetime
import json
import os
from pathlib import Path
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

import profile_api as profile

PREFIX = profile.PREFIX + '/admin'
SERVICE = None
LOCK = threading.Lock()


class AdminError(Exception):
    def __init__(self, status, code):
        self.status, self.code = status, code


def native(token, path, method='GET', payload=None):
    request = urllib.request.Request(profile.NAV + path, method=method,
        data=json.dumps(payload).encode() if payload is not None else None,
        headers={'X-ND-Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
    try:
        with profile.HTTP.open(request, timeout=8) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024: raise AdminError(502, 'NATIVE_UNAVAILABLE')
            return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raise AdminError(401 if error.code == 401 else 403 if error.code == 403 else 503,
                         'NATIVE_REJECTED') from None
    except (OSError, ValueError):
        raise AdminError(503, 'NATIVE_UNAVAILABLE') from None


class Accounts:
    def __init__(self, state, database, library, imports=None, legacy=None, cancel=None, legacy_guard=None):
        self.state, self.database, self.library = Path(state), Path(database), Path(library)
        self.imports, self.legacy, self.cancel = Path(imports) if imports else None, legacy or (lambda _: False), cancel
        self.legacy_guard = legacy_guard or (lambda: False)
        self.folder = self.state / 'accounts'
        self.folder.mkdir(mode=0o700, parents=True, exist_ok=True)
        if self.folder.resolve() != self.folder or self.folder.is_symlink(): raise ValueError('Physical account state required')
        self.path = self.folder / 'accounts.sqlite'
        with self.db() as db:
            db.executescript('''CREATE TABLE IF NOT EXISTS tombstones(name TEXT PRIMARY KEY,uid TEXT NOT NULL,created TEXT NOT NULL,quantum_uid TEXT);
                CREATE TABLE IF NOT EXISTS provisioned(uid TEXT PRIMARY KEY,name TEXT NOT NULL UNIQUE);
                CREATE TABLE IF NOT EXISTS uploads(uid TEXT NOT NULL,path TEXT PRIMARY KEY,name TEXT NOT NULL,bytes INTEGER NOT NULL,created TEXT NOT NULL);''')
            if 'quantum_uid' not in [r[1] for r in db.execute('PRAGMA table_info(tombstones)')]:
                db.execute('ALTER TABLE tombstones ADD COLUMN quantum_uid TEXT')
        self.path.chmod(0o600)

    def db(self):
        db = sqlite3.connect(self.path, timeout=1)
        db.row_factory = sqlite3.Row
        return _database(db)

    def blocked(self, username):
        with self.db() as db: return db.execute('SELECT 1 FROM tombstones WHERE name=?', (username.casefold(),)).fetchone() is not None

    def registered(self, uid=None, username=None):
        with self.db() as db:
            row = db.execute('SELECT uid,name FROM provisioned WHERE ' + ('uid=?' if uid is not None else 'name=?'),
                             (uid if uid is not None else username.casefold(),)).fetchone()
        return bool(row and not self.blocked(row['name']))

    def quantum_blocked(self, uid, username):
        with self.db() as db:
            return db.execute('SELECT 1 FROM tombstones WHERE name=? OR quantum_uid=?',
                              (username.casefold(), str(uid))).fetchone() is not None

    def native_blocked(self, uid, username):
        with self.db() as db:
            return db.execute('SELECT 1 FROM tombstones WHERE name=? OR uid=?',
                              (username.casefold(), str(uid))).fetchone() is not None

    def identity(self, identity):
        user = native(identity['token'], '/api/user/' + identity['id'])
        if user.get('id') != identity['id'] or self.native_blocked(identity['id'], user.get('userName', '')):
            raise AdminError(401, 'ACCOUNT_REVOKED')
        return user

    def users(self, token):
        rows = native(token, '/api/user?_start=0&_end=1001')
        if not isinstance(rows, list) or len(rows) > 1000: raise AdminError(503, 'ACCOUNT_LIMIT')
        for user in rows:
            if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', str(user.get('id', ''))): raise AdminError(502, 'NATIVE_UNAVAILABLE')
        return rows

    def list(self, identity):
        rows = self.users(identity['token']); admins = sum(row.get('isAdmin') is True for row in rows)
        return {'users': [self.dto(row, identity['id'], admins) for row in rows]}

    def dto(self, user, own, admins):
        return {'id': user['id'], 'username': user['userName'], 'isAdmin': user.get('isAdmin') is True,
                'uploadCount': self.uploads(user['id'], 0, 0)['total'],
                'canDelete': user['id'] != own and not (user.get('isAdmin') is True and admins <= 1)
                    and (not self.legacy(user['userName']) or self.legacy_guard())}

    def uploads(self, uid, offset, limit):
        # SQL pagination/counts avoid materializing libraries merely to show a badge.
        with self.db() as db:
            attached = self.imports is not None and self.imports.is_file()
            if attached:
                db.execute('ATTACH DATABASE ? AS imports', (self.imports.resolve().as_uri() + '?mode=ro',))
            manual = "SELECT name,bytes AS size,created AS createdAt,'upload' AS source,path AS sortkey FROM uploads WHERE uid=?"
            imported = "SELECT substr(d.path,-320),d.bytes,min(j.updated),'yandex',d.id FROM imports.items i JOIN imports.jobs j ON j.id=i.job JOIN imports.downloads d ON d.id=i.id WHERE j.uid=? AND i.outcome='downloaded' AND d.origin='published' GROUP BY d.id"
            sql = manual + (' UNION ALL ' + imported if attached else '')
            params = (uid, uid) if attached else (uid,)
            total = db.execute('SELECT count(*) FROM (' + sql + ')', params).fetchone()[0]
            rows = [dict(row) for row in db.execute('SELECT name,size,createdAt,source FROM (' + sql + ') ORDER BY createdAt,source,sortkey LIMIT ? OFFSET ?', params + (limit, offset))] if limit else []
        for row in rows:
            if row['source'] == 'yandex': row['name'] = Path(row['name']).name
        return {'items': rows, 'total': total}

    def record(self, identity, path, name, size):
        actual = Path(path)
        if actual.resolve() != actual or self.library not in actual.parents or not actual.is_file() or actual.stat().st_size != size:
            raise AdminError(503, 'PUBLICATION_UNCONFIRMED')
        with self.db() as db:
            db.execute('INSERT INTO uploads VALUES(?,?,?,?,?)', (identity['id'], str(actual), name[:320], size, iso(time.time())))

    def backup(self):
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
        destination = self.folder / ('before-account-' + stamp + '.sqlite')
        if not self.database.is_file() or self.database.resolve() != self.database: raise AdminError(503, 'BACKUP_UNAVAILABLE')
        started = time.monotonic()
        def progress(status, remaining, total):
            if status in (5, 6) or time.monotonic() - started > 12: raise AdminError(503, 'BACKUP_BUSY')
            time.sleep(.002)
        with contextlib.closing(sqlite3.connect(self.database.as_uri() + '?mode=ro', uri=True, timeout=.25)) as source:
            with contextlib.closing(sqlite3.connect(destination)) as output:
                source.backup(output, pages=256, progress=progress, sleep=.01)
                if output.execute('PRAGMA quick_check').fetchone()[0] != 'ok': raise AdminError(503, 'BACKUP_UNAVAILABLE')

    def create(self, identity, data):
        if set(data) != {'username', 'password'}: raise AdminError(400, 'INVALID_REQUEST')
        name, password = data['username'], data['password']
        if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{3,32}', name): raise AdminError(400, 'INVALID_USERNAME')
        if not isinstance(password, str) or not 12 <= len(password) or len(password.encode()) > 128 or any(ord(c) < 32 for c in password):
            raise AdminError(400, 'INVALID_PASSWORD')
        if self.blocked(name) or self.legacy(name): raise AdminError(409, 'USERNAME_UNAVAILABLE')
        if any(row['userName'].casefold() == name.casefold() for row in self.users(identity['token'])):
            raise AdminError(409, 'USERNAME_UNAVAILABLE')
        libraries = native(identity['token'], '/api/library?_start=0&_end=1001')
        if not isinstance(libraries, list) or not 0 < len(libraries) <= 1000: raise AdminError(503, 'LIBRARY_UNAVAILABLE')
        ids = [row['id'] for row in libraries]
        self.backup()
        user = native(identity['token'], '/api/user', 'POST', {'userName': name, 'name': name, 'password': password, 'isAdmin': False})
        uid = user.get('id', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', uid): raise AdminError(503, 'ACCOUNT_SETUP_INCOMPLETE')
        try:
            native(identity['token'], '/api/user/' + uid + '/library', 'PUT', {'libraryIds': ids})
            current = native(identity['token'], '/api/user/' + uid)
            if current.get('userName') != name or current.get('isAdmin') is not False or sorted(x['id'] for x in current.get('libraries', [])) != sorted(ids):
                raise AdminError(503, 'ACCOUNT_SETUP_INCOMPLETE')
            with self.db() as db: db.execute('INSERT INTO provisioned VALUES(?,?)', (uid, name.casefold()))
        except BaseException:
            # Only this exact newly-created ordinary identity is eligible for compensation.
            with contextlib.suppress(Exception):
                current = native(identity['token'], '/api/user/' + uid)
                if current.get('id') == uid and current.get('userName') == name and current.get('isAdmin') is False:
                    native(identity['token'], '/api/user/' + uid, 'DELETE')
            raise
        return {'user': self.dto(current, identity['id'], 1), 'uploadReady': True, 'warning': None}

    def delete(self, identity, uid):
        rows = self.users(identity['token']); user = next((r for r in rows if r['id'] == uid), None)
        if uid == identity['id']: raise AdminError(409, 'SELF_DELETE_FORBIDDEN')
        if user is None: raise AdminError(404, 'USER_NOT_FOUND')
        if user.get('isAdmin') is True and sum(r.get('isAdmin') is True for r in rows) <= 1:
            raise AdminError(409, 'LAST_ADMIN_FORBIDDEN')
        quantum_uid = self.legacy(user['userName'])
        if quantum_uid and not self.legacy_guard(): raise AdminError(503, 'UPLOAD_REVOCATION_UNAVAILABLE')
        self.backup()
        with self.db() as db:
            db.execute('INSERT OR IGNORE INTO tombstones(name,uid,created,quantum_uid) VALUES(?,?,?,?)',
                       (user['userName'].casefold(), uid, iso(time.time()), str(quantum_uid) if quantum_uid else None))
        if self.cancel: self.cancel(uid)
        # A failed/uncertain deletion stays upload-revoked and may be retried; never report success early.
        native(identity['token'], '/api/user/' + uid, 'DELETE')
        if any(row['id'] == uid for row in self.users(identity['token'])): raise AdminError(503, 'DELETE_UNCONFIRMED')
        return {'ok': True}


@contextlib.contextmanager
def _database(db):
    try:
        with db: yield db
    finally: db.close()


def iso(value):
    if isinstance(value, str):
        return value[:40]
    return datetime.datetime.fromtimestamp(float(value), datetime.timezone.utc).isoformat().replace('+00:00', 'Z')


def configure(state, database, library, **kwargs):
    global SERVICE
    SERVICE = Accounts(state, database, library, **kwargs)
    return SERVICE


def dispatch(handler, chunks):
    parsed = urllib.parse.urlsplit(handler.path)
    if not parsed.path.startswith(PREFIX + '/'): return None
    try:
        identity = profile.authenticate(handler.headers)
        user, _ = profile.native(identity['token'], '/api/user/' + identity['id'])
        if user.get('id') != identity['id'] or user.get('isAdmin') is not True: raise AdminError(403, 'ADMIN_REQUIRED')
        if SERVICE is None: raise AdminError(503, 'ADMIN_UNAVAILABLE')
        route = parsed.path[len(PREFIX):]
        query = urllib.parse.parse_qs(parsed.query, strict_parsing=True)
        if handler.command == 'GET' and route == '/me' and not query:
            value = {'isAdmin': True}
        elif handler.command == 'GET' and route == '/users' and not query:
            value = SERVICE.list(identity)
        elif handler.command == 'GET' and re.fullmatch(r'/users/[A-Za-z0-9_-]{1,80}/uploads', route):
            if set(query) - {'offset', 'limit'} or any(len(v) != 1 for v in query.values()): raise AdminError(400, 'INVALID_REQUEST')
            offset, limit = int(query.get('offset', ['0'])[0]), int(query.get('limit', ['50'])[0])
            if not 0 <= offset <= 1_000_000 or not 1 <= limit <= 100: raise AdminError(400, 'INVALID_REQUEST')
            value = SERVICE.uploads(route.split('/')[2], offset, limit)
        elif handler.command in ('POST', 'DELETE') and not query:
            if not LOCK.acquire(blocking=False): raise AdminError(429, 'ACCOUNT_BUSY')
            try:
                if handler.command == 'POST' and route == '/users':
                    raw = bytearray()
                    for block in chunks(handler.rfile, handler.headers):
                        raw.extend(block)
                        if len(raw) > 16384: raise AdminError(413, 'INVALID_REQUEST')
                    data = json.loads(raw)
                    if not isinstance(data, dict): raise AdminError(400, 'INVALID_REQUEST')
                    value = SERVICE.create(identity, data)
                elif handler.command == 'DELETE' and re.fullmatch(r'/users/[A-Za-z0-9_-]{1,80}', route):
                    value = SERVICE.delete(identity, route.split('/')[2])
                else: raise AdminError(405, 'METHOD_NOT_ALLOWED')
            finally: LOCK.release()
        else: raise AdminError(405, 'METHOD_NOT_ALLOWED')
        if len(json.dumps(value,separators=(',',':')).encode())>512*1024:raise AdminError(503,'RESPONSE_LIMIT')
        profile.send(handler, 200, value); return 200
    except profile.ProfileError as error:
        profile.send(handler, error.status, {'error': {'code': 'AUTH_REQUIRED'}}); return error.status
    except AdminError as error:
        profile.send(handler, error.status, {'error': {'code': error.code}}); return error.status
    except (OSError, sqlite3.Error, ValueError, TypeError, KeyError):
        profile.send(handler, 503, {'error': {'code': 'ADMIN_UNAVAILABLE'}}); return 503
