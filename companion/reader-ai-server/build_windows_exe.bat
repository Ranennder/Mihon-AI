@echo off
setlocal
cd /d "%~dp0"

set "RUNTIME_DIR=%MIHONAI_RUNTIME_DIR%"
if not defined RUNTIME_DIR set "RUNTIME_DIR=%TEMP%\mihon-realesrgan-runtime"

if not exist "%RUNTIME_DIR%\realesrgan-ncnn-vulkan.exe" (
  echo Preparing Windows runtime in %RUNTIME_DIR%
  powershell -ExecutionPolicy Bypass -File "%~dp0prepare_windows_runtime.ps1" -RuntimeDir "%RUNTIME_DIR%"
  if errorlevel 1 exit /b 1
)

if not exist "%RUNTIME_DIR%\models\realesr-animevideov3-x2.bin" (
  echo Runtime models not found in %RUNTIME_DIR%
  exit /b 1
)

set "PYTHON_CMD=py -3"
where py >nul 2>&1 || set "PYTHON_CMD=python"

call %PYTHON_CMD% -m pip install --upgrade pip pillow pyinstaller
call %PYTHON_CMD% -m PyInstaller ^
  --clean ^
  --onefile ^
  --name MihonAiCompanion ^
  --add-data "%RUNTIME_DIR%;runtime" ^
  reader_ai_companion.py

echo.
echo Built exe: dist\MihonAiCompanion.exe
