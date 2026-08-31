. (Join-Path $PSScriptRoot "common.ps1")
Assert-Prerequisites

$imageArchives = Get-ChildItem (Join-Path $script:PackageRoot "images") -Filter "*.tar"
if (-not $imageArchives) { throw "images 디렉터리에 Docker 이미지 TAR 파일이 없습니다." }

foreach ($archive in $imageArchives) {
    Write-Host "Docker 이미지 불러오는 중: $($archive.Name)"
    docker load -i $archive.FullName
    if ($LASTEXITCODE -ne 0) { throw "Docker 이미지 로드에 실패했습니다: $($archive.Name)" }
}

Invoke-Compose up -d
Invoke-Compose ps
Write-Host "SQLAdvisor 설치가 완료되었습니다."

