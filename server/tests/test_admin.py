# SPDX-License-Identifier: GPL-3.0-or-later
import copy
import contextlib
import io
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'api'))
import admin_api as admin
import profile_api as profile
import server
from email.message import Message


class Native:
    def __init__(self):
        self.rows = {'owner': {'id': 'owner', 'userName': 'Owner', 'isAdmin': True},
                     'friend': {'id': 'friend', 'userName': 'Friend', 'isAdmin': False}}
        self.mutations = []
        self.fail_assignment = False

    def __call__(self, token, path, method='GET', payload=None):
        if method != 'GET': self.mutations.append((path, method, copy.deepcopy(payload)))
        if path.startswith('/api/user?'): return copy.deepcopy(list(self.rows.values()))
        if path.startswith('/api/library?'): return [{'id': '1'}, {'id': '2'}]
        if path == '/api/user' and method == 'POST':
            self.rows['new'] = {'id': 'new', **payload}; self.rows['new'].pop('password')
            return copy.deepcopy(self.rows['new'])
        uid = path.split('/')[3]
        if path.endswith('/library'):
            if self.fail_assignment: raise admin.AdminError(503, 'NATIVE_UNAVAILABLE')
            self.rows[uid]['libraries'] = [{'id': x} for x in payload['libraryIds']]; return {}
        if method == 'DELETE': self.rows.pop(uid); return None
        return copy.deepcopy(self.rows[uid])


class AdminTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name).resolve()
        self.db = self.root / 'native.sqlite'
        with contextlib.closing(sqlite3.connect(self.db)) as db:
            db.execute('CREATE TABLE preserved(id)'); db.execute('INSERT INTO preserved VALUES(42)'); db.commit()
        self.api = admin.Accounts(self.root, self.db, self.root / 'music')
        self.owner = {'id': 'owner', 'token': 'secret-never-returned'}
        self.native = Native(); self.mock = patch.object(admin, 'native', self.native); self.mock.start()

    def tearDown(self):
        self.mock.stop(); self.tmp.cleanup()

    def test_regular_create_all_libraries_private_password_not_persisted(self):
        result = self.api.create(self.owner, {'username': 'NewFriend', 'password': 'a-safe-test-password'})
        self.assertTrue(result['uploadReady']); self.assertFalse(result['user']['isAdmin'])
        self.assertTrue(self.api.registered(uid='new')); self.assertFalse(self.api.registered(uid='friend'))
        self.assertEqual(self.native.rows['new']['libraries'], [{'id': '1'}, {'id': '2'}])
        self.assertEqual(len(list(self.api.folder.glob('before-account-*.sqlite'))), 1)
        self.assertNotIn(b'a-safe-test-password', self.api.path.read_bytes())

    def test_compensate_library_assignment_failure_exact_new_user_only(self):
        self.native.fail_assignment = True
        with self.assertRaises(admin.AdminError): self.api.create(self.owner, {'username': 'NewFriend', 'password': 'a-safe-test-password'})
        self.assertNotIn('new', self.native.rows); self.assertIn('friend', self.native.rows)

    def test_delete_keeps_audio_and_tombstones_reuse(self):
        audio = self.root / 'music' / 'retained.mp3'; audio.parent.mkdir(); audio.write_bytes(b'audio')
        self.assertEqual(self.api.delete(self.owner, 'friend'), {'ok': True})
        self.assertEqual(audio.read_bytes(), b'audio'); self.assertTrue(self.api.blocked('FRIEND'))
        with self.assertRaises(admin.AdminError) as caught:
            self.api.create(self.owner, {'username': 'Friend', 'password': 'a-safe-test-password'})
        self.assertEqual(caught.exception.status, 409)

    def test_self_and_legacy_denied_before_any_mutation(self):
        self.api.legacy = lambda name: name.lower() == 'friend'
        for uid, code in [('owner', 'SELF_DELETE_FORBIDDEN'), ('friend', 'UPLOAD_REVOCATION_UNAVAILABLE')]:
            with self.assertRaises(admin.AdminError) as caught: self.api.delete(self.owner, uid)
            self.assertEqual(caught.exception.code, code)
        self.assertFalse(self.native.mutations)
        self.assertFalse(self.api.blocked('Friend'))
        self.assertFalse(next(x for x in self.api.list(self.owner)['users'] if x['id'] == 'friend')['canDelete'])

    def test_provenance_pagination_no_legacy_invention(self):
        folder = self.root / 'music'; folder.mkdir()
        for i in range(12):
            path = folder / (str(i) + '.mp3'); path.write_bytes(b'ok')
            self.api.record({'id': 'friend'}, path, path.name, 2)
        self.assertEqual(self.api.uploads('owner', 0, 100), {'items': [], 'total': 0})
        self.assertEqual(len(self.api.uploads('friend', 5, 3)['items']), 3)
        self.assertEqual(self.api.uploads('friend', 0, 0), {'items': [], 'total': 12})

    def test_current_native_role_required_not_caller_claim(self):
        handler = type('Handler', (), {'path': admin.PREFIX + '/users', 'headers': {}, 'command': 'GET'})()
        fake = {'id': 'friend', 'token': 'token', 'isAdmin': True}
        with patch.object(profile, 'authenticate', return_value=fake), patch.object(profile, 'native', return_value=(self.native.rows['friend'], {})), patch.object(profile, 'send') as send:
            self.assertEqual(admin.dispatch(handler, lambda *_: []), 403)
            self.assertEqual(send.call_args.args[1], 403)

    def test_failed_delete_stays_revoked_and_does_not_return_success(self):
        original = self.native.__call__
        def failing(token, path, method='GET', payload=None):
            if method == 'DELETE': raise admin.AdminError(503, 'NATIVE_UNAVAILABLE')
            return original(token, path, method, payload)
        with patch.object(admin, 'native', failing):
            with self.assertRaises(admin.AdminError): self.api.delete(self.owner, 'friend')
        self.assertTrue(self.api.blocked('Friend')); self.assertIn('friend', self.native.rows)

    def test_upload_final_validator_checks_tombstone_after_receive_started(self):
        folder=self.root/'music';folder.mkdir()
        user={'id':'friend','token':'private'}
        def receive(chunks,stage,publish,name,validator,identifier):
            with self.api.db() as db:db.execute('INSERT INTO tombstones(name,uid,created) VALUES(?,?,?)',('friend','friend','now'))
            validator(Path('fake'))
            self.fail('revoked upload must not publish')
        with patch.object(admin,'SERVICE',self.api),patch.object(server,'LIBRARY',folder),patch.object(server.audio,'receive',receive), \
                patch.object(server.audio,'safe_directory',return_value=3),patch.object(server.audio.os,'close'), \
                patch.object(server.audio,'validate',return_value=1):
            with self.assertRaises(admin.AdminError) as failure:server.upload(user,'source=Uploads&path=/a.mp3&override=false',[b'a'])
        self.assertEqual(failure.exception.status,401)

    def test_native_uploader_login_with_configured_policy(self):
        headers=Message();headers['X-Password']='test-password'
        response=type('Response',(),{'__enter__':lambda s:s,'__exit__':lambda *args:None,'read':lambda s,cap:b'{"token":"a.b.c"}'})()
        identity={'id':'friend','token':'a.b.c','username':'Friend'}
        with patch.object(admin,'SERVICE',self.api),patch.object(profile.HTTP,'open',return_value=response),patch.object(profile,'authenticate',return_value=identity),patch.object(server,'login_allowed',return_value=True):
            self.assertEqual(server.login('username=Friend',headers),b'a.b.c')


if __name__ == '__main__': unittest.main()
