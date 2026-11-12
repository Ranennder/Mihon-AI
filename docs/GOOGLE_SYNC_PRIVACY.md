# Google Drive history sync and library restore privacy

Effective date: 12 November 2026. Applies to the optional Google Drive history
sync in **Mihon AI** and **Aidoku AI**, maintained by
[Ranennder](https://github.com/Ranennder).

## What you authorize

Connecting Google is optional. The apps request only
`https://www.googleapis.com/auth/drive.appdata`: access to their own hidden
application data in your Google Drive. This permission does not give the apps
access to your other Drive files. Both apps use clients registered in the same
Google Cloud project to exchange history between your devices.

Google handles account selection and consent. The apps do not receive your
Google password. You can continue using the apps without connecting Google.

## Data accessed and purpose

The apps read your Drive account's immutable permission ID and, when supplied by
Google, email address and display name. These identify the connected account,
show it in settings, and prevent one account's history from being applied to
another account. Email and display name are not written into history replicas.

The hidden history files contain an installation UUID, source/title/chapter
identifiers, optional title and chapter names and URLs, page positions and page
counts, read/unread state, last-read dates, and timestamps used to resolve edits
between devices. A replica may contain history merged from your other devices.
Only reading records with matching source/title/chapter identities are applied.
An Android replica also includes a separate complete snapshot of its actual
library favorites: title/source identities, names, original website URLs,
optional library-add dates and a revision timestamp. It includes unread
favorites and titles without chapters. Unresolved sources remain represented.
Incognito reading events are excluded; explicitly favorited titles belong to
the library snapshot regardless of whether they have reading history.
Image files, passwords, OAuth tokens, categories,
extensions and AI settings are not included in these history files.

On iOS, you can explicitly import titles represented in the received history.
The app resolves their original URLs through installed sources, requests title
details and chapter lists from those sites and applies matching reading records.
This history restoration does not add every read title to the library.

Separately, you can explicitly restore the iPhone library from a selected Android
snapshot in the same cloud account, without a manual backup file. The app shows
the selected source and confirms replacement, resolves missing favorites through
installed sources, saves a local recovery backup and changes library membership
only when all favorites resolve. Current iPhone library entries absent from the
selected favorites leave the library; their chapter data and history remain.
An empty snapshot needs separate confirmation. This is a one-time operation;
subsequent history synchronization does not automatically remove iPhone-only
favorites. No image files are transferred through Google Drive by this feature.

This data is used only to provide the history synchronization and library restore
you enable. It is
not sold, used for advertising, or used to train AI models. The AI image
enhancement feature and optional WebDAV connection are separate from Google
history sync.

## Storage and transfer

The apps exchange history directly with Google's HTTPS OAuth and Drive APIs.
There is no developer-operated history relay or database. Google stores the
hidden history in your account under its own terms and privacy policy. A
computer running the image companion is not needed for this exchange.

Local credentials are held in the platform's protected storage: iOS Keychain or
Android's encrypted credential store and Google Play services. Native backups
exclude these sync credentials. OAuth tokens are not embedded in releases,
published source archives, or uploaded history files. The apps retain a local
history journal and pending edits so temporary network failures do not discard
your reading state. History journals and Drive replicas are not separately
end-to-end encrypted by this feature.

Aidoku AI also keeps a bounded local sync/import diagnostics log. It contains
stages, counters, source identifiers, optional title names and classified error
codes. Passwords, OAuth tokens, private URLs and raw server responses are not
recorded in this log. It is excluded from device backups and is not automatically
uploaded. You can filter, clear, copy or share it yourself in settings. A shared
copy can reveal title names; review it before posting it publicly. The general
application log is separate and is also exported only by your action.

## Control and deletion

You can disable automatic sync in either app. Signing out disconnects that
device locally; it does not erase local reading history, delete cloud replicas,
or revoke access on your other phone.

To revoke the Google grant, remove the application's connection in
[Google Account connections](https://myaccount.google.com/connections).
Revocation can require reconnecting both apps. To erase the cloud history, use
Google Drive's **Settings → Manage apps** and delete this application's hidden
data. Before deleting cloud data, disable sync on every connected device:
otherwise retained local history can be uploaded again. Delete local history
separately in each app if you also want it removed from the phones.

Cloud files otherwise remain until you remove them or delete the associated
Google account; local data follows the device's app-data and backup lifecycle.

## Contact and updates

For questions, bugs or deletion guidance, use the
[Mihon AI issue tracker](https://github.com/Ranennder/Mihon-AI/issues) or
[Aidoku AI distribution issue tracker](https://github.com/Ranennder/Aidoku-AI-GBox/issues).
Do not include passwords, tokens or private reading history in public issues.

Changes to the use of Google data will be disclosed here and in the apps;
additional consent will be requested when required. The use and transfer of
information received from Google APIs will adhere to the
[Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy),
including its Limited Use requirements.
