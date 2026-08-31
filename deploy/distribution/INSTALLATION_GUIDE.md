# SQLAdvisor 설치 매뉴얼

## 1. 문서 개요

본 문서는 SQLAdvisor 독립 설치 패키지를 고객사 서버에 설치하고 정상 동작을 확인하는 절차를 설명합니다.

- 제품 버전: 패키지의 `VERSION` 파일 참조
- 설치 방식: Docker Compose 기반 독립 설치
- 외부 인터넷: 설치 시 불필요 (Docker 이미지가 패키지에 포함됨)
- 기본 접속 주소: `http://<설치 서버 IP>:80`

## 2. 패키지 구성

압축을 해제하면 다음 파일이 있어야 합니다.

```text
SQLAdvisor-<버전>/
├─ images/
│  └─ sqladvisor-images-<버전>.tar
├─ config/
├─ nginx/
├─ scripts/
├─ INSTALLATION_GUIDE.md
├─ .env.example
├─ docker-compose.yml
├─ README.md
├─ RELEASE_NOTES.md
├─ SHA256SUMS
└─ VERSION
```

## 3. 설치 전 확인사항

### 3.1 권장 서버 사양

| 구분 | 최소 | 권장 |
| --- | --- | --- |
| 운영체제 | Linux x86_64 | 고객사 표준 Linux x86_64 |
| CPU | 4 Core | 8 Core 이상 |
| 메모리 | 8 GB | 16 GB 이상 |
| 여유 디스크 | 30 GB | 50 GB 이상 + 데이터 증가분 |
| Docker | Docker Engine 24 이상 권장 | 고객사 표준 지원 버전 |
| Compose | Docker Compose v2 | 최신 고객사 표준 버전 |

Windows 환경은 Linux 컨테이너를 실행할 수 있는 Docker 환경이 필요합니다. 운영 서버는 Linux 사용을 권장합니다.

### 3.2 네트워크 및 방화벽

| 출발지 | 목적지 | 포트 | 용도 |
| --- | --- | --- | --- |
| 사용자 PC | SQLAdvisor 서버 | TCP 80 또는 고객사 지정 포트 | 웹 접속 |
| SQLAdvisor 서버 | 분석 대상 Oracle DB | Oracle Listener 포트 | SQL 성능정보 조회 |
| SQLAdvisor 서버 | 선택한 LLM 서비스 | TCP 443 또는 내부 LLM 포트 | AI 분석 |

PostgreSQL, Redis, API 및 Worker 포트는 외부에 공개하지 않습니다.

### 3.3 설치 프로그램 확인

```bash
docker version
docker compose version
```

두 명령이 모두 정상적으로 버전을 출력해야 합니다.

## 4. 파일 무결성 확인

### Linux

패키지 최상위 디렉터리에서 실행합니다.

```bash
sha256sum -c SHA256SUMS
```

결과가 `OK`인지 확인합니다.

### Windows PowerShell

```powershell
Get-FileHash .\images\*.tar -Algorithm SHA256
Get-Content .\SHA256SUMS
```

출력된 SHA-256 값과 `SHA256SUMS`의 값이 동일한지 확인합니다.

## 5. 환경설정

### 5.1 환경파일 생성

Linux:

```bash
cp .env.example .env
vi .env
```

Windows PowerShell:

```powershell
Copy-Item .env.example .env
notepad .env
```

### 5.2 필수 설정

다음 세 항목의 DB 비밀번호는 반드시 동일하게 입력합니다.

```dotenv
POSTGRES_PASSWORD=고객사에서_정한_비밀번호
SPRING_DATASOURCE_PASSWORD=고객사에서_정한_비밀번호
DATABASE_URL=postgresql://sqladvisor:고객사에서_정한_비밀번호@postgres:5432/sqladvisor
```

비밀번호에 `@`, `:`, `/`, `#`, `%` 같은 URL 예약문자를 사용하면 `DATABASE_URL`에서 URL 인코딩이 필요합니다. 최초 설치 시에는 영문, 숫자, `_`, `-` 조합을 권장합니다.

웹 포트를 변경하려면 다음 값을 수정합니다.

```dotenv
HTTP_PORT=80
```

### 5.3 AI 설정 예시

OpenAI 사용 예시:

```dotenv
AWR_LLM_PROVIDER=openai
AWR_EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=고객사_API_KEY
OPENAI_CHAT_MODEL=gpt-4.1-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
```

내부 OpenAI 호환 LLM 사용 예시:

```dotenv
AWR_LLM_PROVIDER=internal
AWR_EMBEDDING_PROVIDER=internal
INTERNAL_LLM_API_KEY=고객사_API_KEY
INTERNAL_LLM_BASE_URL=http://내부LLM주소:포트
INTERNAL_LLM_CHAT_MODEL=고객사_모델명
INTERNAL_EMBEDDING_BASE_URL=http://내부임베딩주소:포트
INTERNAL_EMBEDDING_MODEL=고객사_임베딩모델명
```

SQL과 실행계획이 외부 LLM 서비스로 전달될 수 있으므로 외부 LLM 사용 전에 고객사 보안정책을 확인해야 합니다.

### 5.4 인증 설정

초기 폐쇄망 설치 확인 단계에서는 다음 값으로 시작할 수 있습니다.

```dotenv
APP_AUTH_ENABLED=false
```

사용자 접근이 가능한 운영 환경에서는 인증을 활성화하고 고객사 인증 방식을 구성해야 합니다.

```dotenv
APP_AUTH_ENABLED=true
APP_AUTH_MODE=external
```

인증 연동값은 고객사 인증체계에 따라 별도 협의가 필요합니다.

## 6. 설치

### 6.1 Linux

패키지 최상위 디렉터리에서 실행합니다.

```bash
chmod +x scripts/*.sh
./scripts/install.sh
```

### 6.2 Windows PowerShell

관리자 권한 PowerShell에서 패키지 최상위 디렉터리로 이동한 후 실행합니다.

```powershell
Set-ExecutionPolicy -Scope Process Bypass
.\scripts\install.ps1
```

설치 스크립트는 다음 작업을 자동 수행합니다.

1. Docker 및 Compose 사용 가능 여부 확인
2. `.env` 작성 여부와 `CHANGE_ME` 잔존 여부 확인
3. 패키지에 포함된 Docker 이미지 로드
4. SQLAdvisor 컨테이너 기동
5. 컨테이너 상태 출력

## 7. 설치 확인

### 7.1 컨테이너 확인

Linux:

```bash
./scripts/status.sh
```

Windows PowerShell:

```powershell
.\scripts\status.ps1
```

다음 컨테이너가 실행 중이어야 합니다.

| 컨테이너 | 역할 |
| --- | --- |
| `sqladvisor-nginx` | 웹 화면 및 API 프록시 |
| `sqladvisor-api` | SQLAdvisor API |
| `sqladvisor-worker` | AWR 분석 Worker |
| `sqladvisor-postgres` | SQLAdvisor 내부 DB |
| `sqladvisor-redis` | 작업 Queue |

PostgreSQL과 Redis는 `healthy` 상태가 표시되는지 확인합니다.

### 7.2 웹 접속 확인

```text
http://<설치 서버 IP>:<HTTP_PORT>
```

예시:

```text
http://192.168.10.20:80
```

다음 항목을 순서대로 확인합니다.

1. SQLAdvisor 초기 화면 표시
2. 로그인 또는 메인 화면 진입
3. Oracle DB 연결 등록 및 연결 테스트
4. SQL 조회
5. SQL 직접 분석 또는 AWR 분석 실행

## 8. 운영 명령

### Linux

```bash
./scripts/start.sh
./scripts/stop.sh
./scripts/status.sh
./scripts/logs.sh
./scripts/logs.sh api
```

### Windows PowerShell

```powershell
.\scripts\start.ps1
.\scripts\stop.ps1
.\scripts\status.ps1
.\scripts\logs.ps1
.\scripts\logs.ps1 api
```

로그 화면은 `Ctrl+C`로 종료합니다. `Ctrl+C`는 컨테이너를 중지하지 않습니다.

## 9. 장애 확인

### 9.1 웹 접속 불가

1. `status` 스크립트로 컨테이너 상태 확인
2. `logs` 스크립트로 Web/API 로그 확인
3. `.env`의 `HTTP_PORT` 확인
4. 서버 방화벽과 고객사 네트워크 정책 확인
5. 동일 포트를 사용하는 다른 서비스가 있는지 확인

### 9.2 API 또는 DB 기동 실패

```bash
docker compose --env-file .env -f docker-compose.yml logs --tail 200 postgres api
```

- `.env`의 세 DB 비밀번호가 동일한지 확인합니다.
- 디스크 여유 공간을 확인합니다.
- 기존 PostgreSQL 볼륨을 사용 중이었다면 최초 설정 비밀번호와 현재 `.env` 값이 다를 수 있습니다.

### 9.3 AI 분석 실패

- `AWR_LLM_PROVIDER` 값 확인
- API Key 또는 내부 LLM URL 확인
- 설치 서버에서 LLM 서버까지 통신 가능한지 확인
- API 및 Worker 로그 확인

## 10. 데이터 보존 및 백업

SQLAdvisor 내부 DB, AWR 파일 및 API 로그는 Docker named volume에 저장됩니다. 일반적인 재기동이나 `docker compose down`으로 삭제되지 않습니다.

PostgreSQL 백업 예시:

```bash
docker compose --env-file .env -f docker-compose.yml exec -T postgres \
  pg_dump -U sqladvisor -d sqladvisor > sqladvisor-backup.sql
```

다음 명령은 내부 DB와 AWR 데이터가 포함된 Docker volume을 삭제하므로 실행하지 마세요.

```bash
docker compose down -v
```

## 11. 설치 완료 체크리스트

- [ ] Docker와 Compose 버전 확인
- [ ] 이미지 SHA-256 확인
- [ ] `.env` 생성 및 `CHANGE_ME` 전체 변경
- [ ] DB 비밀번호 세 항목 동일 여부 확인
- [ ] AI 제공자 및 API Key/URL 설정
- [ ] 인증 정책 확인
- [ ] 컨테이너 5개 기동 확인
- [ ] PostgreSQL/Redis Health 확인
- [ ] 웹 화면 접속 확인
- [ ] Oracle DB 연결 테스트
- [ ] SQL 또는 AWR 분석 테스트
- [ ] 백업 경로 및 운영 담당자 지정
