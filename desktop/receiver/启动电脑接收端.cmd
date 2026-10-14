@echo off
chcp 65001 >nul
setlocal
set "HEARLENS_NODE=%USERPROFILE%\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe"
if not exist "%HEARLENS_NODE%" set "HEARLENS_NODE=node.exe"
"%HEARLENS_NODE%" "%~dp0launcher.mjs" start %*
if errorlevel 1 pause
