# SPDX-License-Identifier: GPL-3.0-or-later
#!/usr/bin/env python3
"""Bounded real-audio validation and atomic publication shared by all upload accounts."""

import argparse
import contextlib
import datetime
import hashlib
import http.client
import http.server
import json
import math
import os
from pathlib import Path
import re
import shutil
import socket
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
import uuid

BASE = Path(os.environ.get("STORAGE_ROOT", "/storage")).resolve()
MAX_BYTES = 100_000_000
MAX_SECONDS = 1200
RESERVE = 10 * 2**30
HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}))
UPLOAD = threading.BoundedSemaphore(1)
FORMATS = "mp3,mov,aac,flac,ogg,wav,aiff,asf"
EXTENSIONS = {"mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "aiff", "wma"}


class Rejected(Exception):
    def __init__(self, status):
        self.status = status


def filename(query, allow_folders=False):
    values = urllib.parse.parse_qs(query, keep_blank_values=True, strict_parsing=True)
    if (set(values) - {"source", "path", "override"} or any(len(v) != 1 for v in values.values())
            or values.get("source") != ["Uploads"] or values.get("override", ["false"]) != ["false"]):
        raise Rejected(400)
    value = values.get("path", [""])[0]
    if not value.startswith("/"):
        raise Rejected(400)
    value = value[1:]
    parts = value.split("/")
    if (not value or len(value.encode()) > 220 or any(not p or p.startswith(".") for p in parts)
            or not allow_folders and len(parts) != 1 or "\\" in value
            or any(ord(c) < 32 or ord(c) == 127 for c in value)
            or parts[-1].rsplit(".", 1)[-1].lower() not in EXTENSIONS):
        raise Rejected(415)
    return parts[-1]


def body_chunks(stream, headers):
    """Read one unambiguous HTTP body, including unknown-length chunked requests."""
    lengths = headers.get_all("Content-Length", [])
    encodings = headers.get_all("Transfer-Encoding", [])
    if len(lengths) > 1 or len(encodings) > 1 or lengths and encodings:
        raise Rejected(400)
    if encodings:
        if encodings[0].lower().strip() != "chunked":
            raise Rejected(400)
        while True:
            line = stream.readline(128)
            if not line.endswith(b"\r\n") or b";" in line:
                raise Rejected(400)
            try:
                size = int(line[:-2], 16)
            except ValueError:
                raise Rejected(400) from None
            if size < 0:
                raise Rejected(400)
            if not size:
                # Trailers are unnecessary for this API; reject rather than interpret them.
                if stream.readline(3) != b"\r\n":
                    raise Rejected(400)
                return
            while size:
                block = stream.read(min(size, 65536))
                if not block:
                    raise Rejected(400)
                size -= len(block)
                yield block
            if stream.read(2) != b"\r\n":
                raise Rejected(400)
    else:
        try:
            size = int(lengths[0]) if lengths else 0
        except ValueError:
            raise Rejected(400) from None
        if size < 0:
            raise Rejected(400)
        while size:
            block = stream.read(min(size, 65536))
            if not block:
                raise Rejected(400)
            size -= len(block)
            yield block


def probe_limits():
    import resource
    os.nice(19)
    resource.setrlimit(resource.RLIMIT_CPU, (10, 12))
    # Ubuntu's dynamically linked FFmpeg maps large read-only ICU libraries.
    # Limit writable data separately rather than rejecting ordinary audio at loader startup.
    resource.setrlimit(resource.RLIMIT_AS, (1024 * 2**20, 1024 * 2**20))
    resource.setrlimit(resource.RLIMIT_DATA, (128 * 2**20, 128 * 2**20))
    resource.setrlimit(resource.RLIMIT_FSIZE, (512 * 1024, 512 * 1024))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_NOFILE, (32, 32))


def check_probe(info):
    streams = info.get("streams", [])
    audio = [s for s in streams if s.get("codec_type") == "audio"]
    if not audio or any(s.get("codec_type") != "audio" and not (
            s.get("codec_type") == "video" and s.get("disposition", {}).get("attached_pic") == 1)
            for s in streams):
        raise Rejected(415)
    if not set(info.get("format", {}).get("format_name", "").split(",")) & {
            "mp3", "mov", "aac", "flac", "ogg", "wav", "aiff", "asf"}:
        raise Rejected(415)
    try:
        durations = [float(info["format"]["duration"])]
        durations += [float(s["duration"]) for s in audio if "duration" in s]
    except (KeyError, TypeError, ValueError):
        raise Rejected(422) from None
    if any(not math.isfinite(d) or d <= 0 or d > MAX_SECONDS for d in durations):
        raise Rejected(422)
    return max(durations)


def validate(path):
    with path.open("rb") as stream:
        prefix = stream.read(8)
    if prefix.startswith((b"MZ", b"\x7fELF", b"PK\x03\x04", b"Rar!", b"7z\xbc\xaf\x27\x1c")):
        raise Rejected(415)
    try:
        # RLIMIT_FSIZE bounds the child's regular output file before the parent
        # reads it; untrusted tags cannot cause an unbounded PIPE allocation.
        with tempfile.TemporaryFile(dir=path.parent) as output:
            result = subprocess.run([sys.executable, str(Path(__file__).resolve()), "--probe", str(path)],
                stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.DEVNULL, timeout=18)
            output.seek(0)
            raw = output.read(512 * 1024 + 1)
            if result.returncode or len(raw) > 512 * 1024:
                raise Rejected(415)
            return check_probe(json.loads(raw))
    except (subprocess.TimeoutExpired, ValueError, OSError):
        raise Rejected(422) from None


def safe_directory(path):
    if path.resolve() != path or path.is_symlink() or not path.is_dir():
        raise Rejected(503)
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)


def publish_file(temporary, publish, name, validator=validate, identifier=None):
    duration = validator(temporary)
    if shutil.disk_usage(temporary).free < RESERVE:
        raise Rejected(507)
    parent = safe_directory(publish)
    try:
        identifier = identifier or uuid.uuid4().hex
        try:
            os.mkdir(identifier, 0o700, dir_fd=parent)
        except FileExistsError:
            pass
        target = os.open(identifier, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        try:
            try:
                os.link(temporary, name, dst_dir_fd=target, follow_symlinks=False)
            except FileExistsError:
                source_stat = temporary.stat()
                destination_stat = os.stat(name, dir_fd=target, follow_symlinks=False)
                if (source_stat.st_dev, source_stat.st_ino) != (destination_stat.st_dev, destination_stat.st_ino):
                    raise Rejected(409) from None
            os.fsync(target)
        finally:
            os.close(target)
        os.fsync(parent)
    finally:
        os.close(parent)
    return duration


def receive(chunks, staging, publish, name, disk_usage=shutil.disk_usage, validator=validate):
    if disk_usage(staging).free < RESERVE + MAX_BYTES:
        raise Rejected(507)
    parent = safe_directory(publish)
    os.close(parent)
    temporary = None
    try:
        fd, value = tempfile.mkstemp(prefix="audio-", suffix=".pending", dir=staging)
        temporary = Path(value)
        count = 0
        started = time.monotonic()
        with os.fdopen(fd, "wb") as output:
            for block in chunks:
                count += len(block)
                if count > MAX_BYTES:
                    raise Rejected(413)
                if time.monotonic() - started > 600:
                    raise Rejected(408)
                if disk_usage(staging).free < RESERVE + len(block):
                    raise Rejected(507)
                output.write(block)
            output.flush()
            os.fsync(output.fileno())
        if not count:
            raise Rejected(415)
        duration = publish_file(temporary, publish, name, validator)
        return count, duration
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)



if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe", type=Path, required=True)
    args = parser.parse_args()
    probe_limits()
    os.execv("/usr/bin/ffprobe", ["ffprobe", "-v", "error", "-threads", "1",
        "-max_alloc", "33554432", "-protocol_whitelist", "file,pipe", "-format_whitelist", FORMATS,
        "-probesize", "5000000", "-analyzeduration", "10000000",
        "-show_entries", "format=duration,format_name:stream=codec_type,duration:stream_disposition=attached_pic",
        "-of", "json", str(args.probe)])
