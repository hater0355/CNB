$ErrorActionPreference = "Stop"
New-Item -ItemType Directory -Force -Path out | Out-Null
$sources = Get-ChildItem -Recurse -Path src -Filter *.java | ForEach-Object { $_.FullName }
javac -encoding UTF-8 --module-path ../QUAN_LY_LUONG/javafx-sdk-26.0.1/lib --add-modules javafx.controls,javafx.fxml -cp "lib/*" -d out $sources
Write-Host "Compiled CHAT_NOI_BO successfully."
