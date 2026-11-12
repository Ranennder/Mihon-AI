# Mihon AI / Aidoku AI history sync v1

This is live, bidirectional history and library synchronization, separate from full backups.
The transports are Google Drive appDataFolder and HTTPS WebDAV
(Koofr preset for new WebDAV connections). No PC is involved.
Both apps must be updated; stock Mihon/Aidoku do not implement this protocol.

## Document

UTF-8 JSON, maximum 8 MiB per replica, 50,000 records, no credentials. Epoch times
are integer milliseconds in 0...9007199254740991. Device IDs are lowercase UUIDs.

```json
{
  "format": "aidoku-mihon-history",
  "version": 1,
  "deviceId": "00000000-0000-4000-8000-000000000001",
  "records": [{
    "id": "lowercase SHA256 of source + LF + manga + LF + chapter",
    "identity": {"source": "mangabuff", "manga": "/manga/123", "chapter": "/manga/123/chapter/456"},
    "metadata": {"mangaTitle": "Example", "chapterTitle": "Chapter 1"},
    "progress": {"page": 3, "totalPages": 20, "modifiedAt": 1700000000000, "deviceId": "00000000-0000-4000-8000-000000000001"},
    "completion": {"read": false, "modifiedAt": 1700000000000, "deviceId": "00000000-0000-4000-8000-000000000001"},
    "history": {"lastReadAt": 1700000000000, "modifiedAt": 1700000000000, "deviceId": "00000000-0000-4000-8000-000000000001"}
  }]
}
```

`metadata` has optional string fields `mangaTitle`, `chapterTitle`, `mangaUrl`,
`chapterUrl`. It is descriptive and MUST NOT be used for fuzzy matching.
The object itself may be omitted (equivalent to an empty object); explicit null
is invalid. Local stamp writers use the persistent account-scoped replica ID,
while received register stamps retain their original writer IDs.
All three state registers are required. `totalPages` is optional/null when
unknown. `page` is ONE-based, zero means unknown. A Mihon zero-based visited
page N maps to N+1; an unread unvisited chapter maps to zero. Page counts must
never be guessed from the position. `lastReadAt: null` is a history deletion
tombstone, independent of completion and position. Reading duration is not
synchronized in v1; preserve local session counters.

## Optional Android library snapshot

Starting with Mihon AI 0.1.38, an Android writer may include `librarySnapshot`
alongside the existing chapter `records`. The outer document remains version 1.
Older clients can ignore this optional field. Absence means unknown; an explicit
`null` is invalid, and a valid snapshot with `entries: []` means an empty library.
No chapter or reading event is invented for an unread favorite.

The snapshot has `format: "aidoku-mihon-library"`, `version: 1`, `deviceId`,
`modifiedAt` and `entries`. Its writer must match the outer document's UUID.
Each entry contains `id`, `source`, `manga`, required `metadata`, and optional
nullable `dateAdded` in Unix milliseconds. Its ID is lowercase SHA-256 of
`source + "\n" + manga`. Title metadata uses the existing metadata object, with
only `mangaTitle` and `mangaUrl`: chapter fields must be absent or null. A supplied
title must be nonempty and at most 16 KiB of UTF-8; a supplied URL must be a safe
absolute HTTP(S) URL without credentials or a fragment, at most 16 KiB, and agree
with the portable title identity. Unresolved local source identities remain in
the complete set, with a missing URL, rather than silently disappearing.

Android captures all actual favorites, including unread titles and titles with
no cached chapters. It advances an account-scoped logical stamp only when the
canonical payload changes, and ignores `favorite_modified_at`: that native field
also changes on unrelated SQL updates. Canonically duplicate native favorites
may collapse into one true membership. There are at most 20,000 entries and the
entire document still has the existing 8 MiB limit. Invalid, oversized or stale
captures must not partially commit local synchronization state.

This is a snapshot authored by one Android installation, not a chapter register
or a bidirectional membership register. Android attaches only its freshly
captured own snapshot after chapter merging. iOS validates and consumes it from
the original pulled document, before committing downloaded history, and never
re-exports it under an iPhone's writer ID. Account-scoped sidecars compare only
revisions of the same author; several Android installations are selected
explicitly, never elected by wall-clock time. Equal-stamp different payloads
are errors. A missing author does not mean an empty library.

The older explicit cloud-library restore fetches missing title metadata through
installed native sources, including actual chapter lists when available.
Only after every favorite is unambiguously resolved does one transaction make
the local library match the selected complete snapshot. It first saves a local
recovery backup. It changes only library membership; manga/chapter objects,
history, read marks, sessions, downloads and trackers remain. Account, revision,
operation and local-inventory fences prevent late or stale work from pruning
the library. Regular history pulls do not continuously remove later iPhone
favorites. This legacy one-time workflow is not the continuous membership protocol
below. The shared fixture is `history-sync-fixtures/library-snapshot-v1.json`.

## Shared library membership

Mihon AI 0.1.39 and Aidoku AI 0.9.24 add optional `libraryState` to the same v1
document. Both clients merge and forward it, including decisions written by a
different installation. Absence means an older client with unknown capability;
explicit null is invalid. A present state with no records is valid.

```json
"libraryState": {
  "format": "aidoku-mihon-library-state",
  "version": 1,
  "records": [{
    "id": "lowercase SHA256 of source + LF + manga",
    "source": "mangabuff",
    "manga": "/manga/123",
    "metadata": {"mangaTitle": "Example", "mangaUrl": "https://mangabuff.ru/manga/123"},
    "dateAdded": 1700000000000,
    "membership": {
      "included": false,
      "modifiedAt": 1700000001000,
      "deviceId": "00000000-0000-4000-8000-000000000002"
    }
  }]
}
```

An entry uses the same strict title identity and required, title-only metadata
as the Android snapshot. `membership` is a required independent LWW register,
ordered by `(modifiedAt, deviceId)` using UTF-8 byte order. `included: false` is
a durable removal tombstone. Absence from a replica is never a removal.
Equal-stamp different membership values are errors, even if a newer register
would otherwise hide those conflicting versions. Register timestamps and UUIDs
use the existing safe integer/UUID rules. Descriptive metadata merges by the
existing UTF-8 minimum rule; `dateAdded` keeps the earliest known positive date,
and does not change a membership stamp. At most 50,000 membership records fit
inside the existing 8 MiB whole-document limit. The shared cross-language fixture
is `history-sync-fixtures/library-state-v1.json`.

Membership clocks are account-scoped and independent of chapter clocks and
snapshot revisions. Android enrolls its existing actual favorites at timestamp
zero; unread favorites need no synthetic chapter records. Real subsequent
add/remove decisions receive positive logical timestamps. Native SQL triggers
record actual favorite changes in the same transaction, suppress remote echoes,
and retain portable identity bindings after native row or extension removal.
iOS captures explicit user saves with durable pending intents and a unique
persistent-history transaction author proving SQL commit. Failed saves do not
publish decisions; interrupted acknowledgements recover from those receipts.
Persistent-history cleanup must retain unresolved receipts until recovery.

On first iPhone enrollment, one available Android installation supplies the
complete initial favorite set. Several installations require explicit origin
selection. The iPhone's pre-upgrade library is not exported as new decisions:
older history imports could have added read titles there by mistake. Real
post-upgrade local decisions remain queued and participate in the merge. The
initial native cleanup waits until all desired favorites resolve and a local
recovery backup exists. That cleanup never invents removal tombstones for old
iPhone-only entries. After enrollment, neither phone is the permanent master:
each genuine add/remove is merged per title, and a failed addition cannot block
other titles' updates or removals.

Missing titles resolve only through exact verified source/website identities,
never fuzzy name search. Installed sources fetch metadata and available chapter
lists outside the cloud transport operation. Unsupported or temporarily limited
sources remain pending with bounded retries; history exchange continues.
Removing a favorite preserves manga/chapter data, reading history and downloads.
Native backup restoration and factory reset are maintenance operations rather
than mass membership decisions; recovery reconciles the local library with the
account's shared state. Categories, trackers and app settings are not shared.

## Merge and local changes

Merge records by id, then merge progress/completion/history INDEPENDENTLY.
Each register is ordered lexicographically by (modifiedAt, deviceId), using
ASCII comparison of lowercase UUIDs. Identical stamps require identical values;
conflicting values at one stamp are invalid. Metadata selection is deterministic
(lexicographically smallest nonempty value per optional field).
This merge must be commutative, associative and idempotent. Validate a complete
replica before incorporating any of its records. Reject future/invalid schemas
without overwriting their files or discarding local state. Never delete cloud
records simply because the corresponding title is absent on this device.

A local clock is persisted and generates max(systemEpochMs, maximumObserved+1).
Observe incoming stamps before the next local mutation. On first enrollment,
seed existing state using the actual last-read timestamp (or 1 if unknown), NOT
the enrollment time, so an old library cannot override newer cloud decisions.
Pull/merge before applying imported state. Local changes made while networking
is in flight remain in the durable journal and must be merged before applying
and uploading. Serialize sync operations; do not hold a DB transaction over I/O.

Ordinary page progress changes only progress. Actual chapter completion changes
completion to true. Only an explicit local mark-unread/reset changes completion
to false. Manual mark-unread can lower/reset progress and must not be merged
using max(page) or read-OR. History removal changes only history on platforms
where read status is retained. Applying remote state must not generate fresh
stamps, invoke download deletion, tracking side effects, or add reading duration.
Never capture incognito reading.

## Identity

Each identity component is nonempty UTF-8 text <=4096 bytes, with no control
characters. `id` is SHA256 UTF-8(source + "\n" + manga + "\n" + chapter).
Use verified site adapters for known mirrors; otherwise use the lowercased exact
website hostname (remove only `www.`). Preserve path case and identity query
parameters, including translation/branch IDs. Drop fragments and a final slash.
Do not strip all query parameters or identify a chapter solely by its number.
For MangaLib/SlashLib, normalize the verified `/ru/manga/SLUG` and `/SLUG` manga
routes to `/SLUG`; normalize chapter `/ru/SLUG/read/...` to `/SLUG/read/...` and
ignore only `ui`/`page` query parameters. Preserve `bid` and other branch fields.
Source families MangaLib and SlashLib remain distinct.

For the verified GroupLe hosts `a.zazaza.me` and `20.allhen.online`, Mihon AI
0.1.36 and Aidoku AI 0.9.19 exclude the reader access parameter `d` (the current
user's `user_hash`) and the exact reader flag `mtr=true` from identities and URL
metadata. This narrow rule covers a single-segment manga path and its exact
`/<manga>/vol<volume>/<chapter>` routes; it does not alias other hosts. Preserve
every other query key, value and order, including translation/branch fields.
Malformed or duplicate reader parameters and unsupported credential-bearing
routes abort synchronization without discarding pending local changes.

Migrate validated legacy records before merging: rebuild the sanitized identity
hash and merge aliases using their ORIGINAL independent register stamps, checking
all original equal-stamp conflicts before an obsolete alias can be hidden. Keep
progress, explicit unread/reset and history-deletion tombstones. Replace the local
account journal atomically and rewrite only this device's replica; never delete
another device's cloud file. Both clients require the paired update, because an
older client can publish the obsolete credential-bearing identities again.

URL-based identity is permitted only when that URL is UNIQUE within the manga's
chapters. Several Senkuro chapters share one overview URL: never merge them.
Otherwise use an exact verified provider chapter key. If no portable identity
is available, use a platform/source-scoped local identity; retain remote records
unapplied and show their count rather than guess by title or chapter number.
Only apply a record when its identity maps to exactly one local chapter.

## Google Drive replicas

Both platform clients belong to the same Google Cloud project and request only
`https://www.googleapis.com/auth/drive.appdata`. Use native platform authorization;
no client secret belongs in the apps. Resolve the connected user with Drive
`about.get?fields=user(permissionId,emailAddress,displayName)`. Journal identity is
`google-drive:<projectNumber>:<permissionId>`, hashed for local filenames. Email
and display name are labels, not account keys. A cancelled login must leave the
previous connection intact. A silent authorization requiring interaction reports
reconnection in settings; foreground polling must not open consent screens.

Each device writes a direct child of `appDataFolder` named
`aidoku-mihon-history-v1-device-<lowercaseUUID>.json`, with these appProperties:

```json
{"historySyncFormat":"aidoku-mihon-history-v1","historySyncDeviceId":"<lowercaseUUID>"}
```

List with `spaces=appDataFolder`, `trashed=false`, and the exact format property;
paginate completely before accepting data. Reject incomplete searches, duplicate
file IDs or device writers, invalid filenames/properties and mismatched document
writers. Limits are 64 replicas, 8 MiB each and 32 MiB aggregate; bound listing
responses and downloads too. Invalid or incomplete pulls abort before applying
or uploading. Pending local changes remain available for a retry.

Before upload, resolve the current own replica again. PATCH that file with
`uploadType=media`, or create it with `uploadType=multipart`, the exact filename
and properties, and `parents:["appDataFolder"]`. Duplicate own files are errors;
never overwrite a different device. A disappeared update target fails this
operation and is reconsidered at the next complete sync. Skip an unchanged
upload only when the current successful pull contained an identical own replica;
recreate missing replicas. The JSON document and merge rules are unchanged.

Send bearer tokens only to the fixed Google API endpoints, with cookies and
redirects disabled. On HTTP 401 invalidate/refresh and retry once. Serialize
token refresh and fence stale results against disconnect/account replacement.
Local sign-out stops scheduled work and clears local credentials, without
revoking the project grant on the other phone or deleting cloud history.
Switching between Google and WebDAV changes the account namespace and must not
reuse the previous provider's pending journal. Credentials remain device-local.

## WebDAV replicas

The configured URL is the account's existing WebDAV root. Use its child folder
`aidoku-mihon-history-v1/` (MKCOL; 405 means it already exists). Each installation
has its own persistent device ID and writes ONLY `device-<deviceId>.json`.
PROPFIND Depth:1 lists replicas; GET each valid device filename, validate/merge,
apply locally, persist merged journal, then PUT the local device replica. A
replica may carry merged records with their ORIGINAL register stamps. No client
may overwrite another installation's file. This avoids concurrent full-backup
overwrites and does not depend on provider-specific ETag/CAS behavior.

Accept only direct children of the configured folder on the same HTTPS origin;
never forward credentials on redirects. Disable cookies and credential-bearing
redirects. Enforce bounded XML/JSON response sizes and replica count (64).
Authentication is Basic over HTTPS, secrets in platform secure storage and
excluded from backups. Invalid credentials, malformed data, account/full-disk,
offline and TLS errors preserve pending local state and appear in settings.

Sync immediately on foreground and poll every 10 seconds while active. Coalesce
committed local changes for about 1.5 seconds with bounded latency: later events
must not restart the window indefinitely. Serialize manual retries and best
effort background opportunities with the same operation. A no-change upload may
be skipped only when the current successful pull contains an identical own
replica; a missing own file must be recreated. Incomplete DAV listings and a
replica whose writer disagrees with its filename abort the pull before upload.
Queued database applies must be fenced against account changes and disabling.
iOS does not guarantee immediate background
execution; Android periodic WorkManager has a minimum 15-minute interval.
Disabling sync preserves local history and must stop scheduled network work.
