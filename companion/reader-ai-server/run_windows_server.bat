@echo off
setlocal
cd /d "%~dp0"

if exist "MihonAiCompanion.exe" (
  MihonAiCompanion.exe --config reader_ai_server.json
) else (
  py -3 reader_ai_companion.py --config reader_ai_server.json
)
