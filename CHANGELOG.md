# Changelog

## v0.1.26

- Add a Reinstall action for extensions with a confirmed signing-key conflict. Android asks to remove the old extension, then the app starts installation of the replacement using the selected installer.
- Download and validate the complete replacement before requesting removal, including its package, version, repository signing certificate, Android requirements, and extension API compatibility.
- Preserve the reader's library, progress, and source preferences; canceling removal keeps the existing extension.
- Keep pending removal across activity recreation and reject late results from canceled or superseded attempts.
- Show one extension failure at a time with a concise reinstall explanation and copyable diagnostics.

Update an affected extension, tap Reinstall when the signing conflict appears, then confirm the Android dialogs. Ordinary PackageInstaller requires user confirmation; the app does not silently remove extensions. The companion has no functional changes from v0.1.25.

## v0.1.25

- Show extension installation failures in a dialog with copyable details, including Android's native rejection code or message from Legacy, PackageInstaller, and Shizuku.
- Detect incompatible signing certificates before system updates and explain when the extension needs to be reinstalled from the selected repository. Certificate rotation and unavailable metadata are left to Android; extensions are never automatically removed.
- Preserve explicit installer errors and successful results in the MIUI early-callback workaround.
- Ignore outdated installer callbacks and load-verification results after a retry or cancellation.
- Add 26 regression tests for error retention, certificate comparisons, native results, and overlapping update attempts.

This release fixes hidden installation errors. A subsequent phone diagnostic confirmed that the installed AllHentai and the Keiyoushi update have different signing keys. Remove only the AllHentai extension and reinstall it from Keiyoushi; the reader's library remains intact. Other extension failures need their own error details. The companion has no functional changes from v0.1.24.

## v0.1.24

- Queue legacy Android extension installations so Update all opens one confirmation at a time, including on MIUI.
- Verify downloaded update APKs and the installed version before reporting success; refresh pending extensions when installation broadcasts are missed.
- Refresh saved extension store endpoints before browsing, including Keiyoushi's migration to the current catalog format.
- Preserve pending extension installations across activity recreation and release the queue if the system installer cannot open.
- Fix Windows companion instance replacement when the port is occupied, and identify listeners through native Windows APIs.
- Close the previous companion before self-update and restart packaged builds with an independent PyInstaller runtime.
- Test instance replacement with both Python processes and the built Windows executable in CI.

## v0.1.23

- Fix switching from a saved LAN companion to internet access, and show pairing progress or errors in AI settings.
- Wait for an established Cloudflare connection and use HTTP/2 for networks that block QUIC.
- Use asynchronous jobs for single-page HTTPS uploads so GPU processing does not hold a proxy request open.
- Defer original image downloads on the phone in direct companion mode, preserve source cookies, and enable direct downloads in every batch mode.
- Make AI image retry invalidate the cached result and protect the retry from stale chapter responses.
- Resolve duplicate extension versions consistently, refresh installed versions after updates, and fix installation cancellation races.
- Fall back to phone uploads for remaining pages when an accepted direct chapter download fails, and prevent different page uploads from reusing the same companion job.
- Fetch complete Git history in Android CI builds so the APK version code increases between releases.

## v0.1.22

- Use direct website-to-PC downloads for single-page remote upscaling instead of preparing and uploading the image on the phone.
- Fall back automatically to the phone upload path when a website refuses the companion's direct request.
- Log accepted direct jobs and source downloads visibly in the Windows companion console.

## v0.1.21

- Report real byte progress while images travel from a website to the PC, from the phone to the PC, and back to the phone.
- Report whole-chapter upscale progress from the number of pages actually produced by Real-ESRGAN.
- Use an indeterminate bar without a fake percentage when a server omits the file size or Real-ESRGAN cannot measure a single image internally.
- Animate measured progress and stage changes, and fade the progress panel out over the completed image.

## v0.1.20

- Update the Mihon base from 0.20.2 to 0.20.4.
- Replace the reader's generic loading spinner during AI processing with a labeled progress bar.
- Show whether an image is being sent to the PC, downloaded by the PC, upscaled, or returned to the phone.
- Track AI progress separately for every page, including whole-chapter and direct-download modes.
- Fix relative dates that could incorrectly appear as today.
- Fix partial MyAnimeList dates and reader pages that could load indefinitely.
- Add category filters to Updates and Upcoming, plus the latest WebView compatibility fixes.
- Preserve Mihon AI remote upscaling, diagnostics, companion auto-update, and HTTP 429 library retry behavior.

## v0.1.19

- Fix Windows self-update replacement failing while the PyInstaller executable is still temporarily locked.
- Retry replacement for up to one minute, verify the installed file size, and write a visible `.update.log` beside the companion.

## v0.1.18

- Add a persistent AI performance journal in Mihon with request durations, status codes, transfer sizes, and safe endpoint details.
- Add copy and clear journal actions to the reader AI settings.
- Correlate phone and companion requests with shared trace IDs, and expand companion chapter timing summaries.

## v0.1.17

- Fix the Windows companion auto-updater failing before its GitHub request because of a missing network import.
- Show the current/latest version check and update errors directly in the companion console.
- Stop suppressing the next update check after an automatic restart.

## v0.1.16

- Fix the one-file Windows companion failing to locate its bundled Cloudflare Tunnel executable.
- Keep Cloudflare Tunnel embedded inside the companion; no separate installation or executable is required.

## v0.1.15

- Bundle an automatic Cloudflare Quick Tunnel into the Windows companion for plug-and-play internet access.
- Pair the phone automatically while it is on the same local network, transferring the temporary HTTPS address and session token without QR codes or manual URL entry.
- Prefer the local companion on Wi-Fi and use the saved internet address when away from the local network.

## v0.1.14

- Add an optional direct-download beta mode for Remote PC whole-chapter upscaling.
- Let the phone pass page URLs, request headers, and matching site cookies to the companion so source images travel directly from the site to the PC.
- Fall back to the existing phone upload path for unsupported requests or direct-download failures.

## v0.1.13

- Add an optional HTTP 429 retry strategy for library updates.
- Wait a random 5–15 seconds before one immediate retry.
- Retry entries still rate-limited once more after the main library pass, using the same delay and retry behavior.

## v0.1.12

- Update the Mihon base from `0.20.1` to `0.20.2`.
- Add the Tokyo Night theme and upstream library search improvements.
- Improve the in-app update prompt and resumable image downloads.
- Fix backup restore dropping library entries with duplicate chapters.
- Include upstream extension installation, reader navigation, tracking, and stability fixes.

## v0.1.11

- Update the Mihon base from `0.19.9` to `0.20.1`.
- Add TachiyomiX 1.6 extension and extension-store support.
- Add Hikka and MangaBaka tracking.
- Add configurable vertical chapter navigation and upstream reader, download, backup, and stability fixes.

## v0.1.10

- Update Mihon base version marker to `0.19.9`.
- Pull in upstream dependency updates from Mihon `0.19.9`.
- Add upstream fixes for MAL unapproved-title errors, AniList publishing type display, and tall-image splitting.
- Add a fourth Remote batch mode, `Chapter stream`, which receives completed chapter pages over one long-lived companion connection with polling fallback.

## v0.1.9

- Prefer discrete GPUs for the Windows companion and Real-ESRGAN subprocess.
- Stop forcing Vulkan GPU id `0` by default on hybrid Windows systems.
- Add CI artifacts for APKs and the Windows companion executable.

## v0.1.0

- Initial public Mihon AI release
- AI reader toggle in the top bar
- Remote PC companion workflow for Windows
- Remote model selection with `Fast` and `Detailed`
- Local `GPU` mode on Android
- Reader-side AI caching, prefetch, and chapter bootstrap
