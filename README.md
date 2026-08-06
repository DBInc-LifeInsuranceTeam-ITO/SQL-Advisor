# SQLAdvisor

SQLAdvisor는 Oracle AWR 분석, Direct DB 기반 실시간 SQL 모니터링, AI 기반 SQL 튜닝을 제공하는 웹 애플리케이션입니다.

주요 기능은 다음과 같습니다.

- Oracle AWR HTML/TXT/PDF 업로드 및 구조화
- Top SQL, Wait Event, DB Time 기반 병목 분석
- Advisor Chat 및 규칙 기반 로컬 분석
- Oracle DB 직접 연결 및 Top SQL 조회
- 현재 실행 SQL, 장기 실행 SQL, 주의 SQL, Blocking 세션 실시간 확인
- Active Sessions, CPU, I/O 활동 추이 확인
- SQL text, 실행계획, 인덱스, bind 근거 기반 튜닝 권고
- OpenAI, Gemini, 내부 OpenAI-compatible endpoint, Ollama 연동

## 실행 및 배포

SQLAdvisor는 운영용 Docker Compose 구성을 기준으로 실행합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

Nginx가 정적 프론트엔드를 서빙하고 `/api` 요청을 API 컨테이너로 프록시합니다.

| 구분 | 주소 |
| --- | --- |
| 웹 화면 | `http://<server-host>/` |
| API | `http://<server-host>/api` |
| PostgreSQL | `<server-host>:5432` |

## 기본 사용 흐름

1. `대시보드`에서 등록된 Oracle DB를 선택합니다.
2. 현재 실행 SQL, 장기 실행 SQL, 주의 SQL, Blocking 세션과 DB 활동 추이를 확인합니다.
3. `SQL 튜닝`에서 Direct DB Top SQL을 조회하거나 SQL을 직접 입력해 분석합니다.
4. 필요하면 AWR 리포트를 업로드하고 Top SQL, Wait Event, 분석 결과를 확인합니다.
5. Advisor Chat에서 리포트 기반 질문을 입력해 추가 설명을 받습니다.

대시보드는 현재 2초 주기로 실시간 지표를 갱신하며, 부하 상위 SQL은 별도 주기로 조회합니다.

## 프로젝트 구조

```text
.
├── deploy/          # 운영 Compose, 환경 템플릿, Nginx 설정
├── frontend/        # Vue 3 + TypeScript 웹 화면
├── sqladvisor/      # Spring Boot API 및 Oracle 모니터링/튜닝 로직
└── worker/          # OCR, AWR 텍스트 추출, 비동기 작업
```

## 주요 환경 파일

| 파일 | 설명 |
| --- | --- |
| `deploy/.env.prod.example` | 운영 환경 템플릿 |
| `deploy/.env.prod` | 운영 실제 환경값, Git 제외 |
| `deploy/config/application-prod.yml` | 운영 API 설정 |
| `deploy/docker-compose.prod.yml` | 운영 Docker Compose |
| `deploy/nginx/prod.conf` | 운영 Nginx 설정 |

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

기본값은 로그인 비활성화입니다. 로그인 활성화 시 `APP_AUTH_MODE`에 따라 Google 로그인 또는 내부 AD 계정 식별자 방식을 사용합니다.

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=internal
APP_INTERNAL_AUTH_EMAIL_DOMAIN=internal.local
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

상세 내용은 [AUTH_DESIGN.md](AUTH_DESIGN.md)를 참고합니다.

## 주요 API

기본 prefix는 `/api`입니다.

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/monitoring/dashboard?connectionId={id}` | 실시간 DB 대시보드 조회 |
| `GET` | `/api/db-connections` | Target DB 연결 목록 조회 |
| `POST` | `/api/db-connections` | Target DB 연결 저장 |
| `POST` | `/api/db-connections/test` | Target DB 연결 테스트 |
| `GET` | `/api/sql-tuning/direct/top-sql` | Direct DB Top SQL 후보 조회 |
| `POST` | `/api/sql-tuning/direct/context` | Direct DB SQL_ID 근거 수집 |
| `POST` | `/api/sql-tuning/direct` | Direct DB SQL_ID 튜닝 실행 |
| `POST` | `/api/sql-tuning` | 직접 입력 SQL 튜닝 실행 |
| `POST` | `/api/reports` | AWR 리포트 업로드 |
| `GET` | `/api/reports` | AWR 리포트 목록 조회 |
| `GET` | `/api/reports/{id}` | AWR 리포트 상세 조회 |
| `POST` | `/api/reports/{id}/analyze` | AWR 병목 분석 실행 |
| `POST` | `/api/reports/{id}/chat` | AWR 기반 Advisor Chat |
| `GET` | `/api/config/ai` | AI 설정 조회 |
| `POST` | `/api/config/ai` | AI 설정 저장 |

전체 API는 [API_DOCUMENTATION.md](API_DOCUMENTATION.md)를 참고합니다.

## 운영 명령어

```bash
# 상태 확인
docker compose -f deploy/docker-compose.prod.yml ps

# 로그 확인
docker compose -f deploy/docker-compose.prod.yml logs -f api
docker compose -f deploy/docker-compose.prod.yml logs -f web

# 재빌드
docker compose -f deploy/docker-compose.prod.yml up -d --build

# 종료
docker compose -f deploy/docker-compose.prod.yml down
```

## Git 및 보안 주의

- `deploy/.env.prod`는 커밋하지 않습니다.
- API Key, DB 비밀번호, 운영 URL은 YAML과 소스에 직접 작성하지 않습니다.
- 운영 DB 모니터링 계정은 조회 전용 권한을 사용합니다.
- 실시간 조회 주기를 줄일수록 Oracle 및 API 호출 부하가 증가할 수 있습니다.

## 참고 문서

| 문서 | 내용 |
| --- | --- |
| [사용자 매뉴얼](USER_MANUAL.md) | 화면별 사용법과 운영 흐름 |
| [API 문서](API_DOCUMENTATION.md) | REST API 상세 |
| [기술 스택](TECH_STACK.md) | 구성 요소와 사용 기술 |
| [프론트엔드 README](frontend/README.md) | 화면, 라우팅, 프론트 구조 안내 |
| [배포 README](deploy/README.md) | 운영 Docker Compose 배포 안내 |
| [RAG 아키텍처](RAG_ARCHITECTURE.md) | RAG 저장 및 검색 구조 |
| [로그인 설계](AUTH_DESIGN.md) | 인증 및 사용자 권한 구조 |
