# SPDX-License-Identifier: GPL-3.0-or-later
#!/usr/bin/env python3
"""Self-only avatar API; Navidrome validates each bearer token, images stay outside music."""

import argparse
import base64
import contextlib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = Path(os.environ.get("STATE_ROOT", "/state")).resolve()
IMAGE_PYTHON = Path(sys.executable)
NAV = os.environ.get("NAV_URL", "http://navidrome:4533").rstrip("/")
PREFIX = "/dedsec/profile/v1"
MAX_INPUT = 5_000_000
MAX_OUTPUT = 512 * 1024
MAX_PIXELS = 16_000_000
MAX_DIMENSION = 4096
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
IMAGE_SLOT = threading.BoundedSemaphore(1)


class ProfileError(Exception):
    def __init__(self, status):
        self.status = status


def native(token, path, cap=65536):
    request = urllib.request.Request(NAV + path, headers={"X-ND-Authorization": "Bearer " + token})
    try:
        with HTTP.open(request, timeout=8) as response:
            raw = response.read(cap + 1)
            if len(raw) > cap:
                raise ProfileError(502)
            return json.loads(raw), response.headers
    except urllib.error.HTTPError as error:
        raise ProfileError(401 if error.code in (401, 403, 404) else 502) from None
    except (urllib.error.URLError, OSError, ValueError):
        raise ProfileError(502) from None


def authenticate(headers):
    authorization = headers.get("Authorization", "")
    if not authorization.startswith("Bearer ") or len(authorization) > 8192:
        raise ProfileError(401)
    token = authorization[7:]
    try:
        parts = token.split(".")
        if len(parts) != 3:
            raise ValueError()
        # uid is an untrusted lookup hint until the native API verifies the JWT.
        hint = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
        uid, username = hint["uid"], hint["sub"]
        if not isinstance(uid, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,80}", uid) or not isinstance(username, str):
            raise ValueError()
    except (ValueError, KeyError, TypeError):
        raise ProfileError(401) from None
    user, _ = native(token, "/api/user/" + uid)
    if user.get("id") != uid or user.get("userName") != username:
        raise ProfileError(401)
    return {"id": uid, "token": token, "username": user["userName"], "isAdmin": user.get("isAdmin") is True}


def directory(uid, create=False):
    root = BASE / "profiles"
    folder = root / hashlib.sha256(uid.encode()).hexdigest()
    if create:
        root.mkdir(mode=0o700, exist_ok=True)
        folder.mkdir(mode=0o700, exist_ok=True)
    for path in (BASE, root, folder):
        if path.exists() and (path.is_symlink() or path.resolve() != path or not path.is_dir()):
            raise ProfileError(503)
    return folder


def read_avatar(uid):
    path = directory(uid) / "avatar.jpg"
    try:
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    except FileNotFoundError:
        return b""
    with os.fdopen(fd, "rb") as stream:
        item = os.fstat(stream.fileno())
        if not stat.S_ISREG(item.st_mode) or item.st_nlink != 1 or item.st_uid != os.getuid() or item.st_size > MAX_OUTPUT:
            raise ProfileError(503)
        return stream.read(MAX_OUTPUT + 1)


def library_count(identity):
    _, headers = native(identity["token"], "/api/song?_start=0&_end=1", cap=1024 * 1024)
    try:
        count = int(headers["X-Total-Count"])
        if not 0 <= count <= 100_000_000:
            raise ValueError()
        return count
    except (KeyError, TypeError, ValueError):
        raise ProfileError(502) from None


def me(identity):
    data = read_avatar(identity["id"])
    version = hashlib.sha256(data).hexdigest() if data else ""
    return {"userId": identity["id"], "avatarVersion": version,
            "avatarUrl": PREFIX + "/avatar?v=" + version if version else "",
            "librarySongCount": library_count(identity), "isAdmin": identity.get("isAdmin") is True}


def image_limits():
    import resource
    os.nice(19)
    resource.setrlimit(resource.RLIMIT_CPU, (5, 6))
    resource.setrlimit(resource.RLIMIT_AS, (512 * 2**20, 512 * 2**20))
    resource.setrlimit(resource.RLIMIT_DATA, (192 * 2**20, 192 * 2**20))
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_OUTPUT, MAX_OUTPUT))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))
    affinity = os.sched_getaffinity(0)
    os.sched_setaffinity(0, {max(affinity)})


def reencode(source, destination):
    import warnings
    from PIL import Image, ImageOps
    Image.MAX_IMAGE_PIXELS = MAX_PIXELS
    warnings.simplefilter("error", Image.DecompressionBombWarning)
    with Image.open(source, formats=["JPEG", "PNG", "WEBP"]) as image:
        width, height = image.size
        if (not 0 < width <= MAX_DIMENSION or not 0 < height <= MAX_DIMENSION
                or width * height > MAX_PIXELS or getattr(image, "is_animated", False)
                or getattr(image, "n_frames", 1) != 1):
            raise ValueError("Unsupported image geometry or animation")
        image.verify()
    with Image.open(source, formats=["JPEG", "PNG", "WEBP"]) as image:
        image.load()
        ImageOps.exif_transpose(image, in_place=True)
        if image.mode in ("RGBA", "LA") or "transparency" in image.info:
            rgba = image if image.mode == "RGBA" else image.convert("RGBA")
            background = Image.new("RGB", rgba.size, "white")
            background.paste(rgba, mask=rgba.getchannel("A"))
            image = background
        else:
            image = image if image.mode == "RGB" else image.convert("RGB")
        image = ImageOps.fit(image, (512, 512), method=Image.Resampling.LANCZOS)
        # A new JPEG with no EXIF/ICC/comments or source metadata.
        clean = Image.new("RGB", image.size)
        clean.paste(image)
        clean.save(destination, format="JPEG", quality=85, optimize=False, progressive=False)
    if not 0 < Path(destination).stat().st_size <= MAX_OUTPUT:
        raise ValueError("Image output limit")


def put_avatar(identity, chunks):
    if not IMAGE_SLOT.acquire(blocking=False):
        raise ProfileError(429)
    try:
        if shutil.disk_usage(BASE).free < 10 * 2**30 + MAX_INPUT:
            raise ProfileError(507)
        folder = directory(identity["id"], create=True)
        worker = IMAGE_PYTHON
        if not worker.is_file():
            raise ProfileError(503)
        with tempfile.TemporaryDirectory(prefix="image-", dir=folder) as temporary:
            source, output = Path(temporary) / "input", Path(temporary) / "output.jpg"
            started = time.monotonic()
            total = 0
            with source.open("xb") as stream:
                for block in chunks:
                    total += len(block)
                    if total > MAX_INPUT:
                        raise ProfileError(413)
                    if time.monotonic() - started > 60:
                        raise ProfileError(408)
                    if shutil.disk_usage(BASE).free < 10 * 2**30 + len(block):
                        raise ProfileError(507)
                    stream.write(block)
            if not total:
                raise ProfileError(415)
            try:
                result = subprocess.run([str(worker), str(Path(__file__).resolve()), "--image-worker", str(source), str(output)],
                    stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=8)
            except (subprocess.TimeoutExpired, OSError):
                raise ProfileError(422) from None
            if result.returncode or not output.is_file() or output.stat().st_size > MAX_OUTPUT:
                raise ProfileError(415)
            # Re-check identity after decoding: password changes/revocation must take effect.
            native(identity["token"], "/api/user/" + identity["id"])
            target = folder / "avatar.jpg"
            if target.is_symlink():
                raise ProfileError(503)
            os.chmod(output, 0o600)
            with output.open("rb") as final:
                os.fsync(final.fileno())
            output.replace(target)
        return me(identity)
    finally:
        IMAGE_SLOT.release()


def delete_avatar(identity):
    if not IMAGE_SLOT.acquire(blocking=False):
        raise ProfileError(429)
    try:
        # Validate the exact existing file before removing only this user's current avatar.
        if read_avatar(identity["id"]):
            (directory(identity["id"]) / "avatar.jpg").unlink()
        return me(identity)
    finally:
        IMAGE_SLOT.release()


def send(handler, status, value, image=False):
    data = value if image else json.dumps(value, separators=(",", ":")).encode()
    handler.close_connection = True
    handler.send_response(status)
    handler.send_header("Content-Type", "image/jpeg" if image else "application/json")
    handler.send_header("Content-Length", str(len(data)))
    handler.send_header("Cache-Control", "private, no-store")
    handler.send_header("X-Content-Type-Options", "nosniff")
    handler.send_header("Connection", "close")
    if image:
        handler.send_header("ETag", '"' + hashlib.sha256(data).hexdigest() + '"')
    handler.end_headers()
    handler.wfile.write(data)


def dispatch(handler, chunks):
    parsed = urllib.parse.urlsplit(handler.path)
    if not parsed.path.startswith(PREFIX + "/"):
        return None
    if parsed.path.startswith(PREFIX + "/admin/"):
        import admin_api
        return admin_api.dispatch(handler, chunks)
    if parsed.path.startswith(PREFIX + "/radio/"):
        import radio
        return radio.dispatch(handler, chunks)
    status = 500
    try:
        if parsed.path not in (PREFIX + "/me", PREFIX + "/avatar"):
            raise ProfileError(404)
        values = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
        if values and (parsed.path.endswith("/me") or set(values) != {"v"}
                       or len(values["v"]) != 1 or not re.fullmatch(r"[a-f0-9]{64}", values["v"][0])):
            raise ProfileError(400)
        if handler.command not in (("GET",) if parsed.path.endswith("/me") else ("GET", "PUT", "DELETE")):
            raise ProfileError(405)
        identity = authenticate(handler.headers)
        if parsed.path.endswith("/me"):
            value = me(identity)
        elif handler.command == "GET":
            value = read_avatar(identity["id"])
            if not value:
                raise ProfileError(404)
            send(handler, 200, value, image=True)
            return 200
        elif handler.command == "PUT":
            if handler.headers.get("Content-Length") and int(handler.headers["Content-Length"]) > MAX_INPUT:
                raise ProfileError(413)
            value = put_avatar(identity, chunks(handler.rfile, handler.headers))
        else:
            value = delete_avatar(identity)
        send(handler, 200, value)
        status = 200
    except ProfileError as error:
        status = error.status
        send(handler, status, {"error": "profile_request_rejected"})
    except (OSError, ValueError, KeyError, TypeError):
        status = 503
        send(handler, status, {"error": "profile_unavailable"})
    return status


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image-worker", nargs=2, metavar=("INPUT", "OUTPUT"))
    args = parser.parse_args()
    if not args.image_worker:
        parser.error("This module is served by the music gateway")
    os.umask(0o077)
    try:
        image_limits()
        reencode(*args.image_worker)
    except Exception:
        raise SystemExit(1) from None
