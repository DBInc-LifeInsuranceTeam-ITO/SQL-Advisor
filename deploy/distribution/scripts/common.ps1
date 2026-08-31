$ErrorActionPreference = "Stop"
$script:PackageRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$script:ComposeFile = Join-Path $script:PackageRoot "docker-compose.yml"
$script:EnvFile = Join-Path $script:PackageRoot ".env"

function Assert-Prerequisites {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw "Docker를 찾을 수 없습니다. Docker를 먼저 설치하세요."
    }
    docker compose version | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Docker Compose v2를 사용할 수 없습니다."
    }
    if (-not (Test-Path $script:EnvFile)) {
        throw ".env 파일이 없습니다. .env.example을 .env로 복사하고 설정하세요."
    }
    $envText = Get-Content $script:EnvFile -Raw
    if ($envText -match "CHANGE_ME") {
        throw ".env의 CHANGE_ME 값을 모두 변경하세요."
    }
}

function Invoke-Compose {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    Push-Location $script:PackageRoot
    try {
        & docker compose --env-file $script:EnvFile -f $script:ComposeFile @Arguments
        if ($LASTEXITCODE -ne 0) { throw "Docker Compose 명령이 실패했습니다." }
    } finally {
        Pop-Location
    }
}

