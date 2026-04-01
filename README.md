<div align="center">

<img src="./.github/assets/logo-readme.svg" alt="Mihon AI logo" width="120"/>

# Mihon AI

Android manga, manhwa, and webtoon reader focused on higher-quality AI-upscaled pages.

[![Latest release](https://img.shields.io/github/v/release/Ranennder/Mihon-AI?label=Release&labelColor=111827&color=2563eb)](https://github.com/Ranennder/Mihon-AI/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-16a34a)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/badge/License-Apache%202.0-0f766e?labelColor=111827)](./LICENSE)

[Latest Release](https://github.com/Ranennder/Mihon-AI/releases/latest) | [All Releases](https://github.com/Ranennder/Mihon-AI/releases) | [Windows Companion](./companion/reader-ai-server/README.md) | [Report an Issue](https://github.com/Ranennder/Mihon-AI/issues) | [License](./LICENSE)

</div>

## Overview

Mihon AI is an Android reader built around AI-upscaled pages for manga, manhwa, and webtoons.

## Features

- Reader top-bar `AI` toggle for instant on/off switching.
- `Remote PC` mode with a standalone Windows companion `.exe`.
- Local network auto-discovery when the server URL is left empty.
- `Fast` and `Detailed` remote upscale models.
- On-device `GPU` mode for local and offline usage.
- Reader caching and prefetch to keep AI pages ready ahead of you.
- Separate Mihon AI app versioning.

## Download

Download the latest published build from [Latest Release](https://github.com/Ranennder/Mihon-AI/releases/latest).

You do not need to build from source to use Mihon AI.

If you plan to use `Remote PC`, download both:

- the Android APK for your phone
- the Windows companion `.exe` for your PC

If you are not sure which build to install:

- `arm64` is the right choice for almost every modern Android phone.
- `universal` is the fallback if you specifically want the all-in-one APK.

Recommended files:

- Android: `Mihon-AI-vX.Y.Z-arm64-....apk`
- Windows companion: `MihonAiCompanion-....exe`

Requires Android 8.0 or higher.

## Modes

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

- Good for fully local usage and quick testing.
- Still considered beta.
- Can heat the phone during longer reading sessions.

## Models

`Remote PC` currently ships with two model profiles:

- `Fast`: quicker to process and easier on network/cache. Best for normal daily reading and smoother scrolling.
- `Detailed`: slower and heavier, but usually keeps shadows, textures, and fine linework better.

## Companion

The Windows companion lives in [companion/reader-ai-server](./companion/reader-ai-server). It receives page images from the app, runs upscaling on the PC, and returns the processed result back to the phone.

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

### Windows companion

```bat
cd companion\reader-ai-server
build_windows_exe.bat
```

## Contributing

Issues and pull requests are welcome.

If you report a bug, please include:

- device model
- Android version
- `GPU` or `Remote PC`
- pager or webtoon mode
- screenshots or logs if available

## Credits

- Uses an Apache 2.0 licensed upstream codebase
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
