@echo off
setlocal
cd /d "%~dp0"
powershell -ExecutionPolicy Bypass -File ".\compile.ps1"
if errorlevel 1 exit /b %errorlevel%
java --module-path "..\QUAN_LY_LUONG\javafx-sdk-26.0.1\lib" --add-modules javafx.controls,javafx.fxml -cp "out;src;lib\*" chatapp.ChatApp
