# Mihon AI Companion

Small HTTP server for `Mihon AI` remote upscale mode.

## What it does

- listens on your Windows PC
- accepts page image bytes from Mihon
- runs a local GPU upscale executable
- returns the processed image back to the phone

## Recommended layout

For a dev build with external runtime files, place the server files in one folder, then add your Windows GPU runtime next to them:

```text
reader-ai-server/
  MihonAiCompanion.exe
  reader_ai_server.json
  runtime/
    realsr-ncnn.exe
    models-Real-ESRGANv3-anime/
      x2.bin
      x2.param
```

The default config expects exactly that layout.
The one-file `.exe` resolves both `reader_ai_server.json` and `runtime/` relative to the folder that contains `MihonAiCompanion.exe`, so it can be launched directly or through the batch file.
If the runtime is bundled during the PyInstaller build, the `.exe` also works standalone with no extra files.

## Quick start on Windows

1. Copy `reader_ai_server.example.json` to `reader_ai_server.json`.
2. Put your GPU upscale binary and model files into `runtime/`.
3. Run `run_windows_server.bat`.
4. In Mihon set:
   - `AI backend` -> `Remote PC`
   - `Remote AI server URL` -> `http://YOUR_PC_IP:8765`
   - `Remote AI server token` -> the same token as in `reader_ai_server.json` if you use one

## Build the `.exe`

If you only have the Python script, build a standalone executable on Windows:

```bat
build_windows_exe.bat
```

That uses `PyInstaller` and outputs:

```text
dist\MihonAiCompanion.exe
```

By default the build script bundles a local `runtime/` folder into the one-file executable.
You can also point it at another runtime directory:

```bat
set MIHONAI_RUNTIME_DIR=C:\path\to\runtime
build_windows_exe.bat
```

## Config

Example config:

```json
{
  "host": "0.0.0.0",
  "port": 8765,
  "token": "",
  "mode": "subprocess",
  "binary": "runtime/realesrgan-ncnn.exe",
  "model_dir": "runtime/models",
  "output_format": "jpg",
  "scale": 2,
  "tile_size": 0,
  "gpu_id": 0,
  "jobs": "1:2:2",
  "timeout_seconds": 180,
  "max_workers": 2,
  "max_request_megabytes": 32
}
```

The companion also accepts older runtime layouts with `runtime/realsr-ncnn.exe` and `runtime/models-Real-ESRGANv3-anime`.

## Local test mode

For smoke tests without a GPU runtime, you can run:

```bash
python3 reader_ai_companion.py --mode mock_copy --host 127.0.0.1 --port 8765
```

That just echoes the uploaded image back, which is useful for checking the HTTP pipeline.
