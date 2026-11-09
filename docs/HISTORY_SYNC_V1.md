# Mihon AI / Aidoku AI history sync v1

This is live, bidirectional history synchronization, separate from full backups.
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
