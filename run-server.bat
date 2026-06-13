@echo off
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File "%~dp0run-server.ps1"
pause
