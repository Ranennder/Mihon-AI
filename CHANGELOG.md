# Changelog

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
