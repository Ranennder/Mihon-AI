# Google Drive registration and validation

End users connect one Google account in both apps. They do not need their own
Cloud project. This document is for maintainers of builds using their own OAuth
registration.

## Registration

Use one developer-owned Google Cloud project for both native clients. Enable
Google Drive API, configure Google Auth Platform for an External audience, and
request only `https://www.googleapis.com/auth/drive.appdata`. Set accurate support
contacts and the public [privacy disclosure](GOOGLE_SYNC_PRIVACY.md).
OAuth clients must be created in Cloud Console; do not reuse upstream Mihon's
Firebase project or another application's client ID.

The user release Android package is `app.mihon.remote`. Its existing release
certificate SHA-1 is
`4A:AF:AF:A1:52:77:3B:C3:70:D3:17:AD:F2:31:12:71:89:80:C9:50`.
Register that package/certificate pair as an Android OAuth client. Different
packages or signing certificates need matching registrations. Android uses
Google Identity Services AuthorizationClient from Google Play services. Silent
authorization does not open an account chooser; settings provides the explicit
login/reconnect action.

Register an iOS OAuth client for bundle `app.ranennder.AidokuAI` in the same
project. The native authorization flow uses the system authentication session,
PKCE S256, random state, and a strict reverse-client-ID callback scheme with
`:/oauthredirect`. Token exchange and renewal use public native-client IDs;
there is no OAuth client secret or server refresh-token exchange. The iOS native
client must remain compatible with the bundle identifier used to sign the app.

Testing mode restricts the user list and can expire Drive authorizations after
seven days. Configure the appropriate production audience for ongoing use;
complete any branding requirements that Google actually requests. Do not claim
a successful registration while the project is still unconfigured or awaiting
consent. This feature needs no paid Cloud server or service-account key.

## Public configuration

Android reads the source-controlled root `google-drive-oauth.json`:

```json
{
  "projectNumber": "<same project number>",
  "projectId": "<same project ID>",
  "androidClientId": "<Android native client>.apps.googleusercontent.com",
  "iosClientId": "<iOS native client>.apps.googleusercontent.com"
}
```

iOS reads `GoogleDriveClientID`, `GoogleDriveProjectNumber` and
`GoogleDriveProjectID` in `Aidoku/Info.plist`. Add the reversed iOS client ID to
`CFBundleURLTypes` as its own scheme, preserving the existing `aidoku` scheme.
Both client IDs must have the same project-number prefix. These identifiers are
public application configuration, not passwords. Never include client secrets,
service-account private keys, access tokens or refresh tokens in the repo.

Missing configuration disables the Google connection action with a clear
message; it must not be represented as a working Google-enabled release.
Existing HTTPS WebDAV accounts remain supported.

## Validation before release

Run Android format, release APK/signature and native unit-test CI, plus iOS
native unit/migration tests and unsigned device archive CI. Verify the packaged
configuration against the exact source commit, version/build and public source
archive; publish only after the required workflows succeed.

Mock tests cover pagination, malformed/duplicate files, token renewal, cancelled
login, account replacement, disconnect and replica/aggregate write limits. They
do not establish live cross-client Drive access. On actual devices, sign into
the same Google account and check Android → iOS and iOS → Android reading
position, read/unread changes and history deletion. Check a fresh install,
return to foreground, token expiry/reconnect, offline edits and switching
accounts. Confirm the current Feather signing setup can finish the iOS callback.
Retain this distinction between native CI and live two-device verification in
release notes.

Shared format, file naming and conflict rules are documented in
[HISTORY_SYNC_V1.md](HISTORY_SYNC_V1.md).

Primary references: [Android authorization](https://developer.android.com/identity/authorization),
[Google Sign-In for iOS](https://developers.google.com/identity/sign-in/ios/start-integrating),
[Drive app data](https://developers.google.com/workspace/drive/api/guides/appdata),
[Drive scopes](https://developers.google.com/workspace/drive/api/guides/api-specific-auth).
