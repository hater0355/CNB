$ErrorActionPreference = "Stop"
$appRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $appRoot

powershell -ExecutionPolicy Bypass -File .\compile.ps1

java `
  --module-path "..\QUAN_LY_LUONG\javafx-sdk-26.0.1\lib" `
  --add-modules "javafx.controls,javafx.fxml" `
  -cp "out;src;lib\*" `
  chatapp.ChatApp
