#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Private pull worker for experimental Russian MFA alignment; standard library only.

No native text/log output leaves the temporary directory. Output is explicitly
experimental model-derived timing, not calibrated music confidence.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
import wave

MODEL = "mfa-ru-gmm-v1"
MAX_PCM = 19_200_000
PINS = {
    "runtime/sherpa-onnx-v2.0.2-linux-x64-shared-no-tts/bin/sherpa-onnx-fa": "32b78b98d1582e43d057f5175326ad6e0db737ba533cbe38ad41fefe48919d7a",
    "runtime/sherpa-onnx-v2.0.2-linux-x64-shared-no-tts/lib/libonnxruntime.so": "4b3607aebd1784b26b6f9b20e4bd974c7ab8287043e4d095cb7d2cb40b5e566e",
    "runtime/sherpa-onnx-v2.0.2-linux-x64-shared-no-tts/lib/libsherpa-onnx-c-api.so": "5422d22586945a31e4c503cde5ddfec63e82568b50f20ad213be3c4568bbe7e1",
    "runtime/sherpa-onnx-v2.0.2-linux-x64-shared-no-tts/lib/libsherpa-onnx-cxx-api.so": "7a10a7fb107a09f5dab7e2e40001cf1bcf42128ef82c993209824b86bb5b09eb",
    "model-ru/final.alimdl": "6468ba154e7ca4a2772f8cb544eb4f6bba532e44d2452778253449b24cd61676",
    "model-ru/tree": "19cbd2fb1a0404a214c6b5c1f3d4af6770ecef93a23adb781eb6814caee8b6a5",
    "model-ru/lda.mat": "5bd6d47ca99c40c6dc34b816e87baa9ab9b3bebb9d491490ccd34517633ea44c",
    "model-ru/meta.json": "737457db2e6f0a738979a3551d9f37a5642ca49a2c47cf018c8c85195fd7f754",
    "model-ru/phones.txt": "c850c893171193d56efc4e6fa4194002e270662f4eb540a52263327a2ce7fced",
    "model-ru/lexicon.txt": "146a5df69879359c7fc1df8a8faa8b5eacc3f440c0dc4e5e2e367e972a746ebd",
}


class Rejected(Exception):
    def __init__(self, reason, status="unsupported"):
        self.reason, self.status = reason, status


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify(root):
    """Reject replaced or unpinned executable, shared libraries and model files."""
    result = {}
    for relative, expected in PINS.items():
        path = root / relative
        if path.is_symlink() or not path.is_file() or digest(path) != expected:
            raise RuntimeError("artifact verification failed")
        s = path.stat()
        result[relative] = (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns)
    return result


def unchanged(root, identities):
    for relative, identity in identities.items():
        path = root / relative
        s = path.stat()
        if path.is_symlink() or (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns) != identity:
            raise RuntimeError("artifact replaced")


def tokenize(lines):
    if not isinstance(lines, list) or not 1 <= len(lines) <= 2000:
        raise Rejected("LIMIT")
    result = []
    total = 0
    for line in lines:
        if not isinstance(line, str) or len(line) > 4000:
            raise Rejected("LIMIT")
        text = unicodedata.normalize("NFC", line).lower()
        # Digits, Latin, tags and mixed languages must not be silently discarded.
        if re.search(r"[^а-яё\s.,!?;:—–\-…«»\"'()]+", text):
            raise Rejected("LANGUAGE")
        words = re.findall(r"[а-яё]+(?:-[а-яё]+)*", text)
        if line.strip() and not words:
            raise Rejected("OOV")
        result.append(words)
        total += len(words)
    if not 1 <= total <= 3000:
        raise Rejected("LIMIT")
    return result


def subset_lexicon(source, target, word_lines):
    required = {w for line in word_lines for w in line}
    found = set()
    with source.open(encoding="utf-8") as src, target.open("x", encoding="utf-8") as dst:
        for row in src:
            parts = row.split()
            if len(parts) >= 2 and parts[0] in required:
                dst.write(row if row.endswith("\n") else row + "\n")
                found.add(parts[0])
    if found != required:
        raise Rejected("OOV")


def parse_grid(path, expected, duration_ms):
    """Parse Praat long TextGrid words, requiring exact word order and coverage."""
    data = path.read_text(encoding="utf-8")
    tier = re.search(r'name = "words"(.*?)(?=\n\s*item \[|\Z)', data, re.S)
    if not tier:
        raise Rejected("ALIGNMENT_FAILED", "error")
    pattern = r'intervals \[\d+\]:\s*xmin = ([\d.eE+-]+)\s*xmax = ([\d.eE+-]+)\s*text = "((?:""|[^\"])*)"'
    words, previous_end = [], 0.0
    for start, end, text in re.findall(pattern, tier.group(1)):
        start, end = float(start), float(end)
        if not math.isfinite(start + end) or start < previous_end - 0.001 or end <= start or end * 1000 > duration_ms + 20:
            raise Rejected("ALIGNMENT_FAILED", "error")
        previous_end = end
        if text:
            # Reject compressed/implausibly stretched word paths. These are
            # structural limits, not an estimate of alignment accuracy.
            if end - start < 0.02 or end - start > 8:
                raise Rejected("QUALITY")
            words.append((text.replace('""', '"'), int(round(start * 1000))))
    if [w for w, _ in words] != [w for line in expected for w in line]:
        raise Rejected("ALIGNMENT_FAILED", "error")
    starts, index = [], 0
    for line in expected:
        starts.append(words[index][1] if line else None)
        index += len(line)
    return starts


def acoustic_score(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    values = re.findall(r"alignment: log-like (-?[\d.eE+]+) over (\d+) frames", text)
    if len(values) != 1:
        raise Rejected("ALIGNMENT_FAILED", "error")
    total, frames = float(values[0][0]), int(values[0][1])
    if not math.isfinite(total) or frames < 1:
        raise Rejected("ALIGNMENT_FAILED", "error")
    return total / frames


def native(root, temp, words, deadline):
    binary = root / next(p for p in PINS if p.endswith("/bin/sherpa-onnx-fa"))
    text = temp / "input.txt"
    text.write_text(" ".join(w for line in words for w in line), encoding="utf-8")
    args = [str(binary), "--debug=1", f"--model-dir={root / 'model-ru'}", f"--lexicon={temp / 'lexicon.txt'}", f"--wav={temp / 'audio.wav'}", f"--text-file={text}", f"--out={temp / 'result.TextGrid'}"]
    env = {"PATH": "/usr/bin:/bin", "HOME": str(Path.home()), "LANG": "C.UTF-8", "OMP_NUM_THREADS": "1", "OPENBLAS_NUM_THREADS": "1", "MKL_NUM_THREADS": "1"}
    with (temp / "native.log").open("xb") as log:
        child = subprocess.Popen(args, stdout=log, stderr=log, env=env)
        try:
            code = child.wait(timeout=max(1, deadline - time.monotonic()))
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
    if code:
        raise Rejected("ALIGNMENT_FAILED", "error")


def align(root, job, pcm, identities, work):
    words = tokenize(job["lines"])
    unchanged(root, identities)
    with tempfile.TemporaryDirectory(prefix="job-", dir=work) as name:
        temp = Path(name)
        subset_lexicon(root / "model-ru/lexicon.txt", temp / "lexicon.txt", words)
        if len(pcm) != job["samples"] * 2 or not pcm or len(pcm) > MAX_PCM:
            raise Rejected("LIMIT")
        with wave.open(str(temp / "audio.wav"), "wb") as audio:
            audio.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            audio.writeframes(pcm)
        native(root, temp, words, time.monotonic() + min(360, job["expiresAt"] - time.time() - 30))
        starts = parse_grid(temp / "result.TextGrid", words, job["durationMs"])
        acoustic_score(temp / "native.log")
        # Empirical pilot: correct -50.6368 vs permuted -51.0001/frame.
        # Both paths succeeded. Neither finite score nor full coverage proves
        # correct alignment on singing. Publish only explicitly automatic,
        # reversible cache timing after structural checks, never confidence.
        return starts


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Client:
    def __init__(self, base, token_file):
        url = urllib.parse.urlsplit(base)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("HTTPS endpoint required")
        self.base = base.rstrip("/")
        stat = token_file.stat()
        if stat.st_mode & 0o077 or stat.st_uid != os.getuid() or token_file.is_symlink():
            raise ValueError("private token required")
        self.token = token_file.read_text().strip()
        if not re.fullmatch(r"[0-9a-f]{64}", self.token):
            raise ValueError("invalid worker token")
        self.opener = urllib.request.build_opener(NoRedirect())

    def post(self, route, body, audio=False):
        req = urllib.request.Request(self.base + "/" + route, json.dumps(body).encode(), {"Authorization": "Bearer " + self.token, "Content-Type": "application/json"}, method="POST")
        with self.opener.open(req, timeout=180 if audio else 30) as response:
            limit = MAX_PCM if audio else 512_000
            if audio and response.headers.get_content_type() != "application/octet-stream":
                raise ValueError("audio content type")
            value = response.read(limit + 1)
            if len(value) > limit:
                raise ValueError("response limit")
            return value if audio else json.loads(value)


def validate_job(job):
    if not isinstance(job, dict) or not re.fullmatch(r"[a-f0-9]{48}", str(job.get("lease", ""))):
        raise ValueError("lease schema")
    if job.get("model") != MODEL or job.get("language") != "ru":
        raise Rejected("LANGUAGE")
    for key in ("samples", "durationMs", "expiresAt"):
        if type(job.get(key)) is not int:
            raise ValueError("lease numeric schema")
    if job.get("sampleRate") != 16000 or not 1 <= job["samples"] <= MAX_PCM // 2 or not 1 <= job["durationMs"] <= 600000 or abs(job["samples"] / 16 - job["durationMs"]) > 2000:
        raise Rejected("LIMIT")
    if not re.fullmatch(r"[a-f0-9]{64}", str(job.get("revision", ""))) or job["expiresAt"] - time.time() < 60:
        raise ValueError("expired or invalid revision")
    tokenize(job["lines"])


def save(path, value):
    temp = path.with_suffix(".tmp")
    with temp.open("w", encoding="utf-8") as stream:
        json.dump(value, stream)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temp, path)


def run(args):
    import fcntl
    os.umask(0o077)
    args.state.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = args.state.stat()
    if args.state.is_symlink() or info.st_uid != os.getuid() or info.st_mode & 0o077:
        raise RuntimeError("private state directory required")
    lock = (args.state / "worker.lock").open("a")
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    identities = verify(args.root)
    client = Client(args.base, args.token)
    journal = args.state / "pending.json"
    pending = json.loads(journal.read_text()) if journal.exists() else None
    if pending and "result" not in pending:
        pending["result"] = {"lease": pending["lease"], "model": MODEL, "status": "error", "reason": "ALIGNMENT_FAILED"}
        save(journal, pending)
    backoff = 5
    while True:
        try:
            if pending:
                if pending["expiresAt"] > time.time():
                    try:
                        client.post("result", pending["result"])
                    except urllib.error.HTTPError as error:
                        if error.code not in (404, 409, 410):
                            raise
                journal.unlink(missing_ok=True)
                pending = None
                print("job completed", flush=True)
                if args.once:
                    return
                time.sleep(30)
            job = client.post("lease", {}).get("job")
            if job is None:
                if args.once:
                    print("idle", flush=True)
                    return
                time.sleep(15)
                continue
            # Persist lease before downloading audio or invoking a child.
            if not isinstance(job, dict) or not re.fullmatch(r"[a-f0-9]{48}", str(job.get("lease", ""))) or type(job.get("expiresAt")) is not int:
                raise ValueError("lease schema")
            pending = {"lease": job["lease"], "expiresAt": job["expiresAt"]}
            save(journal, pending)
            try:
                validate_job(job)
                pcm = client.post("audio", {"lease": job["lease"]}, audio=True)
                starts = align(args.root, job, pcm, identities, args.state)
                result = {"lease": job["lease"], "model": MODEL, "status": "aligned", "startsMs": starts, "coverage": 1}
            except Rejected as rejected:
                result = {"lease": job["lease"], "model": MODEL, "status": rejected.status, "reason": rejected.reason}
            except Exception:
                result = {"lease": job["lease"], "model": MODEL, "status": "error", "reason": "ALIGNMENT_FAILED"}
            pending["result"] = result
            save(journal, pending)
            backoff = 5
        except (OSError, ValueError, KeyError, urllib.error.URLError):
            print("request deferred", flush=True)
            if args.once:
                raise SystemExit(1)
            time.sleep(backoff)
            backoff = min(300, backoff * 2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--token", type=Path, required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--once", action="store_true")
    run(parser.parse_args())
