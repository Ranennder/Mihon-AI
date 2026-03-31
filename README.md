<div align="center">

<img src="./.github/assets/logo.svg" alt="Mihon AI logo" title="Mihon AI logo" width="96"/>

# Mihon AI

AI-enhanced Android reader for manga, manhwa, and webtoons.

Built as a focused fork of Mihon with higher-quality AI-upscaled pages, a standalone Windows companion, and a stable upstream base.

[![Latest release](https://img.shields.io/github/v/release/Ranennder/Mihon-AI?label=Release&labelColor=111827&color=2563eb)](https://github.com/Ranennder/Mihon-AI/releases)
[![Base](https://img.shields.io/badge/Base-Mihon%200.19.7-4b5563)](https://github.com/mihonapp/mihon/releases/tag/v0.19.7)
[![Android](https://img.shields.io/badge/Android-8.0%2B-16a34a)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/github/license/Ranennder/Mihon-AI?labelColor=111827&color=0f766e)](./LICENSE)

[Releases](https://github.com/Ranennder/Mihon-AI/releases) | [Windows Companion](./companion/reader-ai-server/README.md) | [License](./LICENSE)

</div>

## What Mihon AI adds

Mihon AI keeps the core Mihon reading experience, but adds an AI-focused reader workflow for people who mainly read manga, manhwa, and webtoons on a phone.

Highlights:

- Top-bar `AI` toggle inside the reader.
- `Remote PC` mode with a standalone Windows companion `.exe`.
- Local network auto-discovery for the companion when the URL field is left empty.
- On-device `GPU` mode for quick testing and fully offline usage.
- `Fast` and `Detailed` remote upscale models.
- Reader-side caching, chapter prefetch, and nearest-page prioritization for smoother scrolling.
- Separate Mihon AI app versioning while still tracking stable Mihon releases.

## Current base

This project is currently based on stable [Mihon `v0.19.7`](https://github.com/mihonapp/mihon/releases/tag/v0.19.7).

The goal is to stay on Mihon stable releases instead of following unreleased upstream commits by default.

## Download

Download the latest APKs from the [Releases page](https://github.com/Ranennder/Mihon-AI/releases).

If you are not sure which APK to install:

- Choose `arm64` for almost all modern Android phones.
- Use `universal` only if you specifically need the all-in-one build.

Requires Android 8.0 or higher.

## Remote PC quick start

`Remote PC` is the recommended mode if you want better quality and less heat on the phone.

1. Install the latest `Mihon AI` APK on your phone.
2. Download the latest standalone `MihonAiCompanion.exe` from Releases.
3. Run the companion on your Windows PC.
4. Make sure the phone and the PC are on the same local network.
5. In reader AI settings, select `Remote PC`.
6. Leave the server URL empty to let Mihon AI auto-discover the companion.
7. Turn on `AI` from the reader top bar.

Notes:

- The companion listens on port `8765`.
- URL auto-discovery works only inside the same LAN.
- If you prefer, you can still enter the server URL manually.

## AI modes

### Remote PC

Best overall quality and the smoothest daily setup once the companion is running.

- `Fast`: closer to the original remote model, lighter and quicker.
- `Detailed`: better at shadows, line detail, and textured areas, but heavier.

### GPU

Runs directly on the phone.

- Good for quick testing or fully local reading.
- Still considered beta.
- Can heat the phone noticeably during long sessions.

## Companion

The Windows companion lives in [companion/reader-ai-server](./companion/reader-ai-server).

It accepts page images from the app, runs local upscaling on the PC, and returns the processed image back to the phone. Current release builds are intended to be usable as a standalone `.exe`.

Companion docs:

- [Windows companion README](./companion/reader-ai-server/README.md)

## Build from source

### Android app

```bash
git clone git@github.com:Ranennder/Mihon-AI.git
cd Mihon-AI
./gradlew assembleDebug
```

On Windows use:

```bat
.\gradlew.bat assembleDebug
```

On Windows, it is safer to build from an ASCII-only path such as `C:\projects\Mihon-AI` instead of a OneDrive path with non-ASCII characters.

### Windows companion

```bat
cd companion\reader-ai-server
build_windows_exe.bat
```

## Roadmap direction

Mihon AI is intentionally focused. The main direction is:

- better remote upscaling quality for manga, manhwa, and webtoons
- smoother reader behavior under fast scrolling
- simple companion setup for normal users
- staying close enough to Mihon stable to keep the base reliable

## Contributing

Issues and pull requests are welcome.

If you are reporting a bug, include:

- device model
- Android version
- whether you used `GPU` or `Remote PC`
- whether the issue happened in pager or webtoon mode
- logs or screenshots if available

## Credits

- Based on [Mihon](https://github.com/mihonapp/mihon)
- Uses the Mihon open source codebase under the Apache 2.0 license
- Remote AI workflow is built around local GPU upscaling runtimes and a Windows companion

## Disclaimer

This application is not affiliated with any content provider and hosts no content.

## License

```text
Copyright 2015 Javier Tomas
Copyright 2024 Mihon Open Source Project
Copyright 2026 Ranennder

Licensed under the Apache License, Version 2.0
```
