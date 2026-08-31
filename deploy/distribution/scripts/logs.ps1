param([string]$Service)
. (Join-Path $PSScriptRoot "common.ps1")
Assert-Prerequisites
if ($Service) { Invoke-Compose logs --tail 200 -f $Service } else { Invoke-Compose logs --tail 200 -f }

