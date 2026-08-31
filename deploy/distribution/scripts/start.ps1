. (Join-Path $PSScriptRoot "common.ps1")
Assert-Prerequisites
Invoke-Compose up -d
Invoke-Compose ps

