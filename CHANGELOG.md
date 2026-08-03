# Changelog

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
