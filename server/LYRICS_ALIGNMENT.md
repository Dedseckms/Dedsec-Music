# Optional automatic lyrics alignment

Disabled by default. A trusted local operator or the pinned owner may enable automatic alignment of real cached plain lyrics with `batch.control_trusted(owner_id, "align_start")` or the authenticated `POST /dedsec/profile/v1/lyrics-batch` body `{"action":"align_start"}`. Use `align_pause` to stop and cancel the current lease. Ordinary lyrics discovery continues independently. Promoted administrators cannot enable this feature.

Only physical songs visible to the current pinned native administrator are eligible. Every extraction, download and result checks current owner/revocation policy, song visibility, physical version, original lyrics revision, plain-only state and absence of any manual timing correction. Existing synchronized lyrics and manual corrections are not overwritten. Blank separator rows are omitted from input and output; every nonempty original line is preserved verbatim and in order. Source labels say `Automatic alignment (MFA)`: these are experimental model-derived timestamps, not human-verified timings or fabricated interpolation.

## Scoped worker protocol

Dedicated `state/lyrics/alignment/worker.token`: exactly 64 lowercase hexadecimal characters, private regular file mode 0600, single hard link, owned by the gateway user. It is separate from native/music/upload/radio credentials. Native JWTs are neither sent to nor stored by the worker.

Only POST requests under `/dedsec/profile/v1/lyrics-align/worker/` accept this token. No redirects or arbitrary URLs are supplied.

- `lease`, body `{}`: returns `{"job":null}` or an opaque job with `lease`, `model:"mfa-ru-gmm-v1"`, `language:"ru"`, `sampleRate:16000`, `samples`, `durationMs`, `lines`, `expiresAt`, and input `revision`.
- `audio`, body `{"lease":"48hex"}`: binary little-endian PCM16 mono 16 kHz, `application/octet-stream`, exact Content-Length, at most 19200000 bytes. No source path, user ID, title, signed URL or provider credentials are exposed.
- `result`, aligned body `{lease,model,status:"aligned",startsMs:[integer...],coverage:1}`. Every nonempty input line needs one finite integer start in original order, monotonically increasing within the song duration. Original text comes from the broker, not the worker.
- `result`, skipped/error body `{lease,model,status:"unsupported"|"error",reason}`. Unsupported reasons: `OOV`, `LANGUAGE`, `LIMIT`, `QUALITY`; error reason: `ALIGNMENT_FAILED`. Unsupported/quality failure never creates timestamps. The worker must validate full word coverage and native acoustic output; dictionary coverage alone is not acoustic confidence.
- `status`, body `{}`: bounded counters only.

One durable lease exists at a time, at least 30 seconds between attempts, with a two-hour expiry. Expired/failed work backs off; at most three attempts per physical source/lyrics revision prevent a bad job blocking the library forever. Busy shared decode capacity does not consume an attempt. Pause/revocation/stale input rejects publication and clears the active lease. Result replay is rejected.

## Resource and storage bounds

Gateway extraction uses the shared decode semaphore when supplied to `Manager(..., decode_slot=...)`. It does not hold a gateway/catalogue/database lock during decoding. The subprocess uses nice 19, one CPU/thread, 512 MiB address space, 20 CPU seconds and a 30-second wall timeout. Only up to ten minutes of local audio is decoded, without network protocols. PCM duration must match catalogue duration within two seconds.

The private broker keeps at most one active PCM file, bounded to 19.2 MB; stale owned opaque PCM files are cleaned during lease recovery. Durable jobs are limited to 100000 records and aligned result JSON to 512 MiB, independently of the shared cache limit. Quota exhaustion pauses new alignment and preserves foreground lyrics and manual corrections. Successful derived results survive restart/cache refresh and are restored into the shared cache when needed; the cache still checks access before serving them.

Owner `lyrics-batch` progress adds `alignment:{enabled,total,pending,done,skipped,errors,active}`. `active` is an integer zero or one. Publishing a result updates that song's batch status to synchronized. No complete-coverage guarantee is made: unsupported language, unavailable source text, OOV words and failed acoustic alignment remain plain or absent.

## Portable worker and provenance

`alignment-worker.py` is a Python 3 standard-library pull worker. Keep its installation root private; use separate private `state` and token files. Run `python3 alignment-worker.py --help` for root, state, token and broker-base options. `--once` processes at most one lease. No Navidrome password or JWT belongs on this worker.

The pinned runtime is [sherpa-onnx v2.0.2](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.2), Linux x64 shared no-TTS build (Apache-2.0); archive SHA-256 `c6725b8994a5e559678f72ddffbf1c14b41fc3f6f650618b98eeb61581b63da2`. Extract under `runtime/`. The model is [MontrealCorpusTools/russian_mfa](https://huggingface.co/MontrealCorpusTools/russian_mfa/tree/28d435e52109e93a916c3b3c07a42b8c340d931e) at revision `28d435e52109e93a916c3b3c07a42b8c340d931e` (CC BY 4.0), under `model-ru/`. Retain upstream licenses and attribution. Downloaded binaries and models are not bundled with the application.

`ru-align-lexicon.py --dictionary PATH --phones ROOT/model-ru/phones.txt --output ROOT/model-ru/lexicon.txt` converts the pinned original dictionary without English-specific phone substitutions. The original dictionary SHA-256 is `b15d7ad6545d228c66fb818d2c66ade035fe50d41c5cd6bba6530d6ac06c4091`; converted output must match `146a5df69879359c7fc1df8a8faa8b5eacc3f440c0dc4e5e2e367e972a746ebd`. The worker checks executable, shared-library and model hashes before requesting a job; its `PINS` lists every required path.

On a small shared machine, use one worker, one native thread, nice 19, a separate CPU affinity, CPUQuota=25%, MemoryMax=768M and MemorySwapMax=0. Limit native BLAS/OpenMP threads to one. Use a dedicated user service with a private writable state directory; do not change the wave analyzer service. Resource limits reduce contention but cannot promise zero effect on shared disk, RAM or CPU. Test the actual native executable on older CPUs before enabling the queue. Missing dictionary entries and unsupported languages are skipped; this release is Russian-only and is not a universal lyrics synchronizer.
