# SQLAdvisor 독립 설치 패키지

이 디렉터리는 SQLAdvisor를 고객사 서버에 독립 설치하기 위한 오프라인 Docker 배포 템플릿입니다. 고객사에는 저장소 전체가 아니라 `build-package` 스크립트가 생성한 압축 파일만 전달합니다.

## 1. 설치 전 준비

| 항목 | 요구 사항 |
| --- | --- |
| 운영체제 | Linux x86_64 권장 (Windows는 Linux 컨테이너 실행환경 필요) |
| Docker | Docker Engine 24 이상 권장 |
| Compose | Docker Compose v2 (`docker compose`) |
| 메모리 | 최소 8 GB, 권장 16 GB 이상 |
| 디스크 | 최소 30 GB + AWR/로그/DB 증가분 |
| 포트 | 기본 HTTP 80 (환경변수로 변경 가능) |

Oracle DB 분석 기능을 사용하려면 설치 서버에서 대상 Oracle DB 주소와 포트로 통신할 수 있어야 합니다. 운영 DB에는 조회 전용 계정을 사용하세요.

배포 이미지는 패키지를 생성한 PC의 CPU 아키텍처를 따릅니다. 일반적인 고객사 Linux 서버용 패키지는 x86_64 환경에서 생성하세요.

## 2. 설치

### Windows PowerShell

```powershell
Copy-Item .env.example .env
notepad .env
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\install.ps1
```

### Linux

```bash
cp .env.example .env
vi .env
chmod +x scripts/*.sh
./scripts/install.sh
```

설치가 끝나면 브라우저에서 `http://<설치서버 IP>:<HTTP_PORT>`로 접속합니다.

## 3. 반드시 변경할 설정

`.env`에서 다음 값을 확인합니다.

- `POSTGRES_PASSWORD`: 영문, 숫자, 특수문자를 조합한 별도 비밀번호
- `AWR_LLM_PROVIDER`: `local`, `openai`, `gemini`, `internal`, `ollama` 중 선택
- 선택한 AI 제공자의 API Key, URL 및 모델명
- `APP_AUTH_ENABLED`: 외부 공개 전 반드시 `true`로 설정하고 인증 방식을 구성
- `HTTP_PORT`: 다른 서비스와 충돌하면 변경

`DATABASE_URL`의 비밀번호에는 URL 예약문자(`@`, `:`, `/`, `#`, `%`)를 사용하지 않는 것을 권장합니다. 사용해야 한다면 URL 인코딩이 필요합니다.

## 4. 운영 명령

| 작업 | Windows | Linux |
| --- | --- | --- |
| 시작 | `.\scripts\start.ps1` | `./scripts/start.sh` |
| 중지 | `.\scripts\stop.ps1` | `./scripts/stop.sh` |
| 상태 | `.\scripts\status.ps1` | `./scripts/status.sh` |
| 로그 | `.\scripts\logs.ps1` | `./scripts/logs.sh` |

특정 서비스 로그만 확인할 수도 있습니다.

```powershell
.\scripts\logs.ps1 api
```

```bash
./scripts/logs.sh api
```

## 5. 데이터 및 백업

PostgreSQL 데이터, AWR 파일, API 로그는 Docker named volume에 보존됩니다. `docker compose down`은 데이터를 지우지 않지만 다음 명령은 데이터를 삭제하므로 실행하지 마세요.

```bash
docker compose down -v
```

PostgreSQL 논리 백업 예시:

```bash
docker compose exec -T postgres pg_dump -U sqladvisor -d sqladvisor > sqladvisor-backup.sql
```

복구 전에는 서비스 중단 및 별도 검증을 권장합니다.

## 6. 업그레이드

1. 기존 DB와 AWR 데이터를 백업합니다.
2. 신규 패키지의 `images/*.tar`를 `docker load`합니다.
3. 기존 `.env`를 신규 `.env.example`과 비교해 추가 설정을 반영합니다.
4. 신규 `docker-compose.yml`, `config`, `nginx` 파일로 교체합니다.
5. `scripts/start`를 실행하고 상태와 로그를 확인합니다.

현재 DB 초기화 SQL은 PostgreSQL 볼륨이 처음 생성될 때만 실행됩니다. 릴리스 노트에 별도 DB 변경 SQL이 포함된 버전은 해당 절차를 먼저 따라야 합니다.

## 7. 보안 주의사항

- `.env`와 DB 접속정보를 메일이나 공개 저장소에 올리지 마세요.
- 외부에는 Web 포트만 허용하고 PostgreSQL, Redis, API, Worker 포트는 공개하지 마세요.
- HTTPS는 고객사 표준 Reverse Proxy 또는 Load Balancer에서 적용하는 것을 권장합니다.
- Oracle 연결 계정은 조회 전용 및 최소 권한으로 발급하세요.
- AI 제공자에게 SQL 원문과 실행계획이 전달될 수 있으므로 고객사 보안정책을 먼저 확인하세요.
- AI가 제시한 SQL은 운영 DB에서 자동 실행하지 말고 검증 DB에서 결과 동일성과 실행계획을 확인하세요.

## 8. 무결성 확인

배포 파일과 함께 제공되는 `SHA256SUMS`로 손상 여부를 확인합니다.

```powershell
Get-FileHash .\images\*.tar -Algorithm SHA256
```

```bash
sha256sum -c SHA256SUMS
```
