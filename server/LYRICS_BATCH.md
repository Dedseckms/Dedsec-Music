# Library lyrics discovery

An optional owner-controlled background pass looks for real lyrics across every physical track visible to the configured `MUSIC_PROTECTED_OWNER_ID`. It checks adjacent UTF-8 `.lrc` files, then exact LRCLIB metadata matches. It does not transcribe audio, fabricate timestamps, or upload music to a provider. Plain text remains plain text when synchronized lyrics are unavailable.

The pass shares the existing single lyrics-fetch worker. Foreground requests are dequeued before the next background job; a currently running provider request finishes normally. Background provider requests are separated by at least two seconds, including GET-to-search fallback. HTTP 429 Retry-After suspends discovery fetches; the deadline is persisted. There are no worker credentials or native JWTs in the saved job.

## API

Only the pinned owner can use these native-bearer endpoints; other users, including promoted administrators, receive 403. Existing account revocation checks apply.

- `GET /dedsec/profile/v1/lyrics-batch`: current progress.
- `POST /dedsec/profile/v1/lyrics-batch` with exactly `{"action":"start"}` or `{"action":"pause"}`: rescan/resume or pause, returning progress. Body limit: 1024 bytes.

```json
{"total":10000,"checked":500,"synced":300,"plain":100,"instrumental":10,"notFound":80,"errors":10,"pending":9500,"running":true,"discovering":false}
```

Start runs bounded keyset discovery in the background, 128 metadata entries per step. `discovering` distinguishes the initial inventory phase; counts settle when that phase completes. Finished physical versions are retained when starting again; new and replaced audio are queued. Errors can be retried with another start after their short negative-cache TTL. Pause allows an in-flight request to finish but does not publish its result. Enable state and physical-version progress survive gateway restarts. Starting again also reconciles removed/inaccessible tracks.

Shared results remain available through the existing authenticated lyrics read endpoint, always checking song visibility first. Owner timing corrections remain in their separate table and are not overwritten. Source replacements or changed lyrics revisions invalidate their application as documented in LYRICS_TIMING.md.

## Bounds and operation

The inventory accepts at most 100000 visible tracks. The shared lyrics cache is bounded to 100000 entries and 512 MiB of UTF-8 JSON payload, with constant-time byte counters and oldest-entry pruning. This excludes SQLite overhead and durable job metadata. Processing does not retain a native JWT or perform credentialed Navidrome lyric requests. Embedded-only lyrics are still discovered lazily when an authorized user opens that track.

A trusted local operator can enable the same job without persisting a JWT, after configuring the protected owner and physical catalogue:

```python
manager = lyrics_api.Manager(state, catalog, start=False, protected_owner=owner_id,
                             background_guard=local_revocation_guard)
try:
    progress = manager.batch.control_trusted(owner_id, "start")
finally:
    manager.close()
```

The separate local revocation guard must reject tombstoned/revoked accounts without requiring a native JWT. Missing background policy fails closed. Each background step also verifies that the pinned owner still has a current native administrator row, and each track verifies current library access. Revoking the owner pauses the job. The running gateway reads that persisted flag on its next idle worker tick. This internal interface requires filesystem/database access; HTTP authentication is unchanged. Do not print tokens or place them in command arguments. A library pass cannot guarantee synchronized coverage for every track: provider availability and actual timestamps determine the result.
