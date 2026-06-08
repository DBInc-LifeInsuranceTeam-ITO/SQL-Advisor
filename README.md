# SQLAdvisor

SQLAdvisor는 Oracle AWR 리포트를 업로드하고 SQL 지표, Wait Event, 분석 결과를 확인하는 웹 애플리케이션입니다. 현재 배포 구성은 `dev`와 `prod` 두 가지이며, 관련 파일은 `deploy/` 폴더에 있습니다.

## 빠른 시작

### Dev로 실행

```bash
sh deploy/init-env.sh --mode dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```

| 구분 | 주소 |
| --- | --- |
| 웹 화면 | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

### Prod로 실행

prod는 Nginx 웹 컨테이너가 정적 프론트엔드를 서빙하고 `/api` 요청을 내부 API로 프록시합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

| 구분 | 주소 |
| --- | --- |
| 웹 화면 | `http://<server-host>/` |
| API | `http://<server-host>/api` |

## 기본 사용법

1. 웹 화면에서 AWR 리포트를 업로드하고 공유 범위를 선택합니다. 공유 리포트는 모든 사용자가 볼 수 있고, 비공유 리포트는 업로드한 본인과 관리자만 볼 수 있습니다.
2. 업로드된 리포트 목록에서 분석할 리포트를 선택합니다.
3. SQL 지표, Wait Event, 분석 결과를 확인합니다.
4. Advisor Chat에서 리포트 기반 질문을 입력해 추가 설명을 받습니다.

## 자주 쓰는 명령어

Dev:

```bash
docker compose -f deploy/docker-compose.dev.yml ps
docker compose -f deploy/docker-compose.dev.yml logs -f api
docker compose -f deploy/docker-compose.dev.yml down
```

Prod:

```bash
docker compose -f deploy/docker-compose.prod.yml ps
docker compose -f deploy/docker-compose.prod.yml logs -f web
docker compose -f deploy/docker-compose.prod.yml logs -f api
docker compose -f deploy/docker-compose.prod.yml down
```

dev와 prod를 전환할 때는 실행 중인 모드를 먼저 `down` 한 뒤 다른 모드를 올립니다.

## 프로젝트 구조

```text
.
├── deploy/          # dev/prod Compose, env 템플릿, Nginx 설정
├── frontend/        # Vue 기반 웹 화면
├── sqladvisor/      # Spring Boot API
└── worker/          # OCR, AWR 텍스트 처리, 보조 작업
```

## 배포 구성

| 모드 | 설명 | 노출 포트 |
| --- | --- | --- |
| `dev` | 로컬 개발/검증용. 웹, API, DB, Redis 포트 노출 | `5173`, `18080`, `5432`, `6379` |
| `prod` | Nginx 웹 배포. API/Redis는 내부 네트워크 통신, DB는 외부 클라이언트 접속용 포트도 노출 | `80`, `5432` |

## 주요 환경 파일

| 파일 | 설명 |
| --- | --- |
| `deploy/.env.dev.example` | dev 환경 템플릿 |
| `deploy/.env.prod.example` | prod 환경 템플릿 |
| `deploy/.env.dev` | dev 실제 환경값, git 제외 |
| `deploy/.env.prod` | prod 실제 환경값, git 제외 |

외부 AI를 사용하지 않을 때:

```env
AWR_LLM_PROVIDER=local
AWR_EMBEDDING_PROVIDER=none
```

OpenAI 예시:

```env
AWR_LLM_PROVIDER=openai
AWR_EMBEDDING_PROVIDER=openai
OPENAI_API_KEY=
OPENAI_CHAT_MODEL=gpt-4.1-mini
OPENAI_EMBEDDING_MODEL=text-embedding-3-small
OPENAI_EMBEDDING_DIMENSION=1536
```

## 로그인 설정

기본값은 로그인 비활성화입니다. 기존처럼 바로 사용하려면 그대로 두면 됩니다.

로그인을 켤 때는 `deploy/.env.prod` 또는 `deploy/.env.dev`에서 `APP_AUTH_ENABLED=true`로 설정하고, `APP_AUTH_MODE`로 외부용/내부용 방식을 선택합니다.

외부용은 Google 로그인을 사용합니다.

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=external
GOOGLE_CLIENT_ID=<Google OAuth Client ID>
APP_ALLOWED_GOOGLE_DOMAINS=
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

내부용은 AD 계정 식별자(`loginEno`)를 사용합니다. 내부 포털이나 프록시가 `loginEno` 쿼리 파라미터 또는 `X-Login-Eno` 헤더로 값을 전달하는 흐름에 맞춥니다.

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=internal
APP_INTERNAL_AUTH_EMAIL_DOMAIN=internal.local
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

- `APP_AUTH_MODE=external`이면 Google 로그인만 사용합니다.
- `APP_AUTH_MODE=internal`이면 Google 로그인 대신 AD 계정 식별자를 사용합니다.
- 내부용에서 `loginEno`가 없으면 로그인하지 않은 상태로 표시되며, 사이드패널에는 대시보드만 표시됩니다.
- `APP_INTERNAL_AUTH_EMAIL_DOMAIN`은 AD 계정 식별자가 이메일 형식이 아닐 때 붙일 기본 도메인입니다.
- `GOOGLE_CLIENT_ID`는 외부용 Google 로그인에서만 필요합니다.
- `APP_ALLOWED_GOOGLE_DOMAINS`는 특정 Google Workspace 도메인만 허용할 때 사용합니다. 비워두면 검증된 Google 계정을 허용합니다.
- `APP_LOCAL_LOGIN_ENABLED`는 향후 로컬 로그인 확장용이며 현재 기본값은 `false`입니다.
- 로그인 상세 설계와 DB 구조는 [로그인 설계 문서](AUTH_DESIGN.md)를 참고합니다.

## API 엔드포인트

기본 API prefix는 `/api`입니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/reports` | AWR 리포트 업로드 |
| `GET` | `/api/reports` | 리포트 목록 조회 |
| `GET` | `/api/reports/{id}` | 리포트 상세 조회 |
| `GET` | `/api/reports/{id}/status` | 분석 상태 조회 |
| `POST` | `/api/reports/{id}/analyze` | 리포트 분석 실행 |
| `POST` | `/api/reports/{id}/chat` | 리포트 기반 채팅 |
| `GET` | `/api/reports/{id}/sql` | SQL 목록 조회 |
| `GET` | `/api/config/ai` | AI 설정 조회 |
| `POST` | `/api/config/ai` | AI 설정 저장 |
| `GET` | `/api/config/ai/models` | 지원 모델 조회 |

## Git 및 보안 주의

- `deploy/.env.dev`, `deploy/.env.prod`는 커밋하지 않습니다.
- prod env는 `deploy/init-env.sh --mode prod`로 생성/검증하고, 실제 Secret은 YAML이나 example 파일에 직접 쓰지 않습니다.
- API Key, DB 비밀번호, 운영 URL은 YAML에 직접 쓰지 않고 환경 파일이나 배포 Secret으로 주입합니다.

## 참고 문서

| 문서 | 내용 |
| --- | --- |
| [배포 README](deploy/README.md) | dev/prod Docker Compose 실행과 운영 Nginx 배포 안내 |
| [프론트엔드 README](frontend/README.md) | Vue 프론트엔드 구조, 로컬 개발, 화면/API 연동 안내 |
| [기술 스택](TECH_STACK.md) | Frontend, Backend, Worker, DB/RAG, AI provider, 배포 기술 정리 |
| [YAML 설정 가이드](YML_CONFIGURATION_GUIDE.md) | dev/prod 설정 파일과 환경 변수 설명 |
| [API 문서](API_DOCUMENTATION.md) | SQLAdvisor API 엔드포인트 상세 |
| [사용자 매뉴얼](USER_MANUAL.md) | 화면 기준 사용 방법과 운영 흐름 |
| [RAG 아키텍처](RAG_ARCHITECTURE.md) | pgvector 기반 RAG 구성도, 인덱싱/검색 흐름, 현재 AI provider 설정 |
| [로그인 설계](AUTH_DESIGN.md) | Google 로그인, 내부용 AD 계정 식별자 로그인, 사용자별 히스토리 DB 구조 |
