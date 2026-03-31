<div align="center">

<img src="./.github/assets/logo.svg" alt="Mihon AI logo" width="96"/>

# Mihon AI

AI-upscaled reading for manga, manhwa, and webtoons on Android.

Built on stable Mihon, focused on one thing: making pages look better without turning the app into a science project.

[![Latest release](https://img.shields.io/github/v/release/Ranennder/Mihon-AI?label=Release&labelColor=111827&color=2563eb)](https://github.com/Ranennder/Mihon-AI/releases)
[![Base](https://img.shields.io/badge/Base-Mihon%200.19.7-4b5563)](https://github.com/mihonapp/mihon/releases/tag/v0.19.7)
[![Android](https://img.shields.io/badge/Android-8.0%2B-16a34a)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/github/license/Ranennder/Mihon-AI?labelColor=111827&color=0f766e)](./LICENSE)

[Releases](https://github.com/Ranennder/Mihon-AI/releases) | [Windows Companion](./companion/reader-ai-server/README.md) | [License](./LICENSE)

</div>

## What Mihon AI is

Mihon AI is a focused fork of Mihon with a dedicated AI reading workflow.

The goal is simple:

- keep the Mihon base stable
- add higher-quality page upscaling
- make `Remote PC` easy enough for normal users
- keep reading fast in real manga, manhwa, and webtoon sessions

This project currently tracks stable [Mihon `v0.19.7`](https://github.com/mihonapp/mihon/releases/tag/v0.19.7).

## What Mihon AI adds

- Reader top-bar `AI` toggle for instant on/off switching.
- `Remote PC` mode with a standalone Windows companion `.exe`.
- Local network auto-discovery when the server URL is left empty.
- `Fast` and `Detailed` remote upscale models.
- On-device `GPU` mode for local testing and offline usage.
- Reader-side caching and chapter prefetch for keeping AI pages ready ahead of you.
- Separate Mihon AI app versioning while still staying on a stable Mihon base.

## Download

Get the latest APKs from the [Releases page](https://github.com/Ranennder/Mihon-AI/releases).

If you are not sure which build to install:

- `arm64` is the right choice for almost every modern Android phone.
- `universal` is the fallback if you specifically want the all-in-one APK.

Requires Android 8.0 or higher.

## Quick Start

### Remote PC

`Remote PC` is the recommended mode if you want the best quality and less heat on the phone.

1. Install the latest `Mihon AI` APK on your phone.
2. Download the latest `Mihon AI Companion` `.exe` from [Releases](https://github.com/Ranennder/Mihon-AI/releases).
3. Run the companion on your Windows PC.
4. Put the phone and PC on the same local network.
5. In reader AI settings, select `Remote PC`.
6. Leave the server URL empty if you want auto-discovery.
7. Turn `AI` on from the reader top bar.

Notes:

- The companion listens on port `8765`.
- Auto-discovery works inside the same LAN.
- You can still set the server URL manually if you prefer.

### GPU

`GPU` runs directly on the phone.

- Good for testing or fully local usage.
- Still considered beta.
- Can heat the phone during longer reading sessions.

## Models

`Remote PC` currently ships with two model profiles:

- `Fast`: lighter and closer to the original remote workflow.
- `Detailed`: better at shadows, line detail, and textured areas, but heavier.

## Companion

The Windows companion lives in [companion/reader-ai-server](./companion/reader-ai-server).

It receives page images from the app, runs upscaling on the PC, and returns the processed result back to the phone.

Companion docs:

- [Windows companion README](./companion/reader-ai-server/README.md)

## Build From Source

### Android app

```bash
git clone git@github.com:Ranennder/Mihon-AI.git
cd Mihon-AI
./gradlew assembleDebug
```

On Windows:

```bat
.\gradlew.bat assembleDebug
```

If you build on Windows, an ASCII-only path such as `C:\projects\Mihon-AI` is safer than a OneDrive path with non-ASCII characters.

### Windows companion

```bat
cd companion\reader-ai-server
build_windows_exe.bat
```

## Roadmap Direction

Mihon AI is intentionally narrow in scope.

The main direction is:

- better remote upscaling quality for manga, manhwa, and webtoons
- stronger reader-side caching and prefetch behavior
- easier Windows companion setup
- staying close to Mihon stable instead of chasing unreleased upstream changes

## Contributing

Issues and pull requests are welcome.

If you report a bug, please include:

- device model
- Android version
- `GPU` or `Remote PC`
- pager or webtoon mode
- screenshots or logs if available

## Credits

- Based on [Mihon](https://github.com/mihonapp/mihon)
- Uses the Mihon codebase under the Apache 2.0 license
- Remote AI workflow is built around local GPU upscaling runtimes and a Windows companion

## Disclaimer

This project is not affiliated with any content provider and hosts no content.

## License

```text
Copyright 2015 Javier Tomas
Copyright 2024 Mihon Open Source Project
Copyright 2026 Ranennder

Licensed under the Apache License, Version 2.0
```
