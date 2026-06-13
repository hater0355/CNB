$ErrorActionPreference = "Stop"
$appRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $appRoot

powershell -ExecutionPolicy Bypass -File .\compile.ps1

java `
  -cp "out;src;lib\*" `
  chatserver.ChatServerApp
