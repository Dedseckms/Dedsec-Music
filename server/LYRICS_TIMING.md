# Shared lyrics timing correction

A song's correction is stored in `state/lyrics/cache.sqlite`, separately from cached provider lyrics and music files. It applies to every authorized listener who can access that song. Source timestamps and audio tags are unchanged.

Set `MUSIC_PROTECTED_OWNER_ID` to the native user ID of the library owner. Missing configuration disables writes. Promoted administrators cannot edit corrections. The existing native bearer authentication and revoked-account guard apply to both endpoints.

## Read

`GET /dedsec/profile/v1/lyrics/{songId}` adds:

```json
{"timingOffsetMs": 750, "canEditTiming": true}
```

The existing `revision` identifies the original lyrics, without the correction. `canEditTiming` is true only for the configured owner when synchronized lyrics are ready. All other users receive false. Pending, absent and unsynchronized lyrics report zero correction and cannot be edited.

## Save

`POST /dedsec/profile/v1/lyrics/{songId}/timing` with the same bearer token:

```json
{"offsetMs": 750, "revision": "64 lowercase hexadecimal characters from the read response"}
```

`offsetMs` is an absolute integer from -10000 through 10000; boolean, decimal and string values are rejected. The body is limited to 1024 bytes and exactly these two fields. Response 200 returns the full current lyrics payload with the saved correction. Positive values advance line highlighting; negative values delay it. Compare playback time plus `timingOffsetMs` with source `startMs`; seeking to a line uses `startMs - timingOffsetMs`.

Errors: 401 invalid/revoked authentication; 403 non-owner or missing owner configuration; 404 inaccessible/missing song; 400 malformed input; 409 stale lyrics revision, expired lyrics cache, unsynchronized lyrics or replaced audio; 413 oversized body. Refresh lyrics after a 409 and require a new explicit save.

Corrections survive restarts and provider cache refreshes when the audio signature and lyrics revision remain identical. Replaced audio or changed source timestamps/text use zero correction until the owner explicitly calibrates that revision. Correction rows are independent of cache eviction, and only one correction row is stored per song.
