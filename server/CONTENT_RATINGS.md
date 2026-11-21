# Content ratings and child mode

Songs use OpenSubsonic `explicitStatus`: `clean`, `explicit`, or an empty string
for unknown. Unknown does not mean clean. A child-mode radio request only admits
known clean songs, before taste calculation, analysis priorities and selection.
There is no server-side mix response cache shared between child and normal mode.
Clients must keep the mode in queue/cache keys and recheck playback separately.

`POST /dedsec/profile/v1/content-ratings` uses the existing native JWT and account
guard. Body: `{"ids":["song-id"]}`, at most500 ASCII alphanumeric/underscore/dash
IDs, each1–80 characters. Response: `{"ratings":{"song-id":"clean"},"overrides":{}}`.
Unknown, missing and inaccessible IDs are equally omitted. No source IDs, paths,
account identities or provider metadata are returned. Existing library visibility
is checked on every request. Request bodies are limited to45KB.

Navidrome native `c`/`e` status is mapped to clean/explicit. The provider overlay
is held in `content-ratings/ratings.sqlite` inside application state, never in
Navidrome's DB. Each row is bound to library ID, exact path, current size, mtime,
device and inode. A changed/missing/symlinked file invalidates that overlay.
Native or overlay explicit evidence wins over contradictory automated clean evidence.
An unknown provider result does not erase an existing explicit rating for the
same physical revision. Verified metadata is retained like an embedded file tag,
with provider provenance and update time, until physical change or an explicit
verified provider refresh; there is no unattended expiry job.

Administrators may set `POST /dedsec/profile/v1/content-ratings/override` with
`{"id":"song-id","status":"explicit"}`; statuses are `explicit`, `clean`, or
`source` to remove the manual override. The existing native administrator and
revocation checks are repeated before writing; client role claims grant nothing.
The current song must be visible and physically present. Missing and inaccessible
songs have the same error. Manual ratings are stored in a separate table with
the physical signature, actor UID and timestamp. They win over all native/provider
metadata, including an explicit source manually marked clean, and automated
backfill cannot overwrite them. A replaced file invalidates the override. Reset
may remove the selected stale override after checking current visibility/file.
Actor identities and signatures never leave private state. Mutation returns the
same batch shape for that song; `overrides` contains only valid current manual
values. Radio song DTOs include boolean `contentRatingOverride` alongside the
effective `explicitStatus`. All listeners see a manual rating for a shared file.

Yandex3.2.2 `content_warning=explicit` or `explicit is True` maps to explicit.
Only `explicit is False` with an absent/empty warning maps to clean. Missing
optional boolean or unfamiliar warning values remain unknown. No inference from
names, lyrics or missing warning alone is used. New imports preserve known ratings
in MP3 ITUNESADVISORY/MP4 rtng tags. Existing audio is never retagged by backfill.

## Existing library backfill

`backfill-content-ratings.py` is an operator-run, resumable metadata-only job.
Run its default dry check first. Supply the exact Navidrome DB, library root,
verified importer downloads DB, shared state root and private0600 token file.
`--apply` is required for provider requests or overlay changes. At most100 IDs are
requested per batch; `--max-batches` defaults to1 and caps each run. Provider calls
are separated by at least1s; hash reads default to16MiB/s. Run with nice19 and idle
I/O priority (`nice -n19 ionice -c3 python3 ...`) on the existing service owner.

Only `legacy_verified` and `published` download rows with exact matching source-ID
paths and full SHA256/size proof are eligible. The file signature is rechecked after
hashing and before committing provider metadata. Ratings are attached by source ID,
never by title matching. File hashes are read once per new physical revision;
current receipts, including unknown results, let a restarted run skip completed
items. A private single-process lock prevents duplicate backfills. An online copy
of the overlay DB is retained before each apply run. Logs contain counts only.
No source token is copied to state or logs. No audio, Navidrome data, scans,
playlists, account roles, service units or other analysis workers are changed.

Before deployment, snapshot current source/configuration and overlay state. Publish
frozen matching `content_ratings.py` in importer, radio and media releases, extend
the host's exact module sets and media route ownership, then respawn only the idle
gateway using its existing guarded deployment helper. Verify unauthenticated401,
authenticated visibility filtering and native/overlay wave DTOs. Start with one
backfill batch and inspect counts/physical guards before the remaining bounded run.
Rollback restores only exact owned source/config candidates and the gateway; keep
overlay provenance and never restore the whole live Navidrome database. Keep unrelated lyrics processing and audio services in their current state.

Primary source semantics: [Yandex SDK3.2.2](https://github.com/MarshalX/yandex-music-api/blob/v3.2.2/yandex_music/track/track.py),
[Navidrome0.64 mapping](https://github.com/navidrome/navidrome/blob/v0.64.0/server/subsonic/helpers.go),
[Navidrome tag aliases](https://github.com/navidrome/navidrome/blob/v0.64.0/resources/mappings.yaml).
