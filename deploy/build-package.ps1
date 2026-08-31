param(
    [Parameter(Mandatory = $true)][ValidatePattern('^[0-9]+\.[0-9]+\.[0-9]+$')][string]$Version,
    [string]$OutputDirectory = "dist"
)
$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$templateRoot = Join-Path $PSScriptRoot "distribution"
$outputRoot = Join-Path $repoRoot $OutputDirectory
$packageName = "SQLAdvisor-$Version"
$packageRoot = Join-Path $outputRoot $packageName
$archivePath = Join-Path $outputRoot "$packageName.zip"

if (Test-Path $packageRoot) { Remove-Item $packageRoot -Recurse -Force }
if (Test-Path $archivePath) { Remove-Item $archivePath -Force }
New-Item -ItemType Directory -Force -Path (Join-Path $packageRoot "images") | Out-Null
Copy-Item "$templateRoot\*" $packageRoot -Recurse -Force
Copy-Item (Join-Path $templateRoot ".env.example") $packageRoot -Force
New-Item -ItemType Directory -Force -Path (Join-Path $packageRoot "config"), (Join-Path $packageRoot "nginx") | Out-Null
Copy-Item "$PSScriptRoot\config\application-prod.yml", "$PSScriptRoot\config\logback-spring.xml" (Join-Path $packageRoot "config")
Copy-Item "$PSScriptRoot\nginx\prod.conf" (Join-Path $packageRoot "nginx")
Set-Content (Join-Path $packageRoot "VERSION") -Value $Version -Encoding utf8
(Get-Content (Join-Path $packageRoot ".env.example") -Raw).Replace('SQLADVISOR_VERSION=1.0.0', "SQLADVISOR_VERSION=$Version") | Set-Content (Join-Path $packageRoot ".env.example") -Encoding utf8
(Get-Content (Join-Path $packageRoot "RELEASE_NOTES.md") -Raw).Replace('SQLAdvisor 1.0.0', "SQLAdvisor $Version") | Set-Content (Join-Path $packageRoot "RELEASE_NOTES.md") -Encoding utf8

Write-Host "SQLAdvisor 이미지를 빌드합니다."
docker build -t "sqladvisor-api:$Version" (Join-Path $repoRoot "sqladvisor")
if ($LASTEXITCODE -ne 0) { throw "API 이미지 빌드 실패" }
docker build -t "sqladvisor-web:$Version" (Join-Path $repoRoot "frontend")
if ($LASTEXITCODE -ne 0) { throw "Web 이미지 빌드 실패" }
docker build -t "sqladvisor-worker:$Version" (Join-Path $repoRoot "worker")
if ($LASTEXITCODE -ne 0) { throw "Worker 이미지 빌드 실패" }

$postgresImage = "sqladvisor-postgres:$Version"
docker build -t $postgresImage -f (Join-Path $PSScriptRoot "postgres.distribution.Dockerfile") (Join-Path $repoRoot "sqladvisor/init-db/postgres")
if ($LASTEXITCODE -ne 0) { throw "PostgreSQL 이미지 빌드 실패" }
docker pull redis:7-alpine
if ($LASTEXITCODE -ne 0) { throw "Redis 이미지 다운로드 실패" }

$imageTar = Join-Path $packageRoot "images/sqladvisor-images-$Version.tar"
docker save -o $imageTar "sqladvisor-api:$Version" "sqladvisor-web:$Version" "sqladvisor-worker:$Version" $postgresImage "redis:7-alpine"
if ($LASTEXITCODE -ne 0) { throw "Docker 이미지 저장 실패" }

$hash = (Get-FileHash $imageTar -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content (Join-Path $packageRoot "SHA256SUMS") -Value "$hash  images/sqladvisor-images-$Version.tar" -Encoding ascii
Compress-Archive -Path "$packageRoot\*" -DestinationPath $archivePath -CompressionLevel Optimal
Write-Host "완료: $archivePath"
