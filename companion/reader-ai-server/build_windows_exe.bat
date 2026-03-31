@echo off
setlocal
cd /d "%~dp0"

set "RUNTIME_DIR=%MIHONAI_RUNTIME_DIR%"
if not defined RUNTIME_DIR set "RUNTIME_DIR=%cd%\runtime"

if not exist "%RUNTIME_DIR%" (
  echo Runtime directory not found: %RUNTIME_DIR%
  echo.
  echo Set MIHONAI_RUNTIME_DIR to a folder containing the Windows upscale runtime,
  echo or place the runtime folder next to this script before building.
  exit /b 1
)

py -3 -m pip install --upgrade pip pyinstaller
py -3 -m PyInstaller ^
  --clean ^
  --onefile ^
  --name MihonAiCompanion ^
  --add-data "%RUNTIME_DIR%;runtime" ^
  reader_ai_companion.py

echo.
echo Built exe: dist\MihonAiCompanion.exe
