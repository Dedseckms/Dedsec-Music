# SPDX-License-Identifier: GPL-3.0-or-later
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'api'))
import profile_api as profile
PIL_AVAILABLE=importlib.util.find_spec('PIL') is not None

class ProfileTests(unittest.TestCase):
    def test_library_total_uses_native_header_not_scan_progress(self):
        with patch.object(profile,'read_avatar',return_value=b''),patch.object(profile,'native',return_value=([{'id':'one'}],{'X-Total-Count':'10599'})) as native:
            result=profile.me({'id':'current','token':'private'})
        self.assertEqual(result,{'userId':'current','avatarVersion':'','avatarUrl':'','librarySongCount':10599})
        native.assert_called_once_with('private','/api/song?_start=0&_end=1',cap=1024*1024)

    def test_real_body_over5mb_retains_existing_avatar_without_worker(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            with patch.object(profile,'BASE',root),patch.object(profile.shutil,'disk_usage',return_value=types.SimpleNamespace(free=30*2**30)),patch.object(profile.subprocess,'run') as worker:
                folder=profile.directory('self',create=True);(folder/'avatar.jpg').write_bytes(b'existing')
                with self.assertRaises(profile.ProfileError) as failure:profile.put_avatar({'id':'self','token':'private'},[b'x'*(5_000_000+1)])
                self.assertEqual(failure.exception.status,413);self.assertEqual((folder/'avatar.jpg').read_bytes(),b'existing');worker.assert_not_called()

    @unittest.skipUnless(PIL_AVAILABLE,'Pillow13 test dependency not installed')
    def test_reencode512_jpeg_strips_metadata(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);source=root/'source.png';output=root/'output.jpg'
            Image.new('RGBA',(20,30),(255,0,0,120)).save(source,format='PNG')
            profile.reencode(source,output)
            with Image.open(output) as image:
                self.assertEqual(image.size,(512,512));self.assertEqual(image.format,'JPEG');self.assertFalse(image.getexif());self.assertNotIn('icc_profile',image.info)

    @unittest.skipUnless(PIL_AVAILABLE,'Pillow13 test dependency not installed')
    def test_oversize_geometry_and_animated_image_rejected(self):
        from PIL import Image
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);wide=root/'wide.png';animation=root/'animation.png';output=root/'out.jpg'
            Image.new('RGB',(4097,1)).save(wide)
            with self.assertRaises(ValueError):profile.reencode(wide,output)
            images=[Image.new('RGB',(2,2),color) for color in ['red','blue']]
            images[0].save(animation,save_all=True,append_images=images[1:],duration=100)
            with self.assertRaises(ValueError):profile.reencode(animation,output)

if __name__=='__main__':unittest.main()
