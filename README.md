# SQLAdvisor

SQLAdvisor는 Oracle AWR 리포트를 업로드하고 SQL 지표, Wait Event, 분석 결과를 확인하는 웹 애플리케이션입니다. 현재 배포 구성은 `dev`와 `prod` 두 가지이며, 관련 파일은 `deploy/` 폴더에 있습니다.

## 빠른 시작

### Dev로 실행

```bash
cp deploy/.env.dev.example deploy/.env.dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```

| 구분 | 주소 |
| --- | --- |
| 웹 화면 | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

### Prod로 실행

prod는 Nginx 웹 컨테이너가 정적 프론트엔드를 서빙하고 `/api` 요청을 내부 API로 프록시합니다.

```bash
cp deploy/.env.prod.example deploy/.env.prod
# deploy/.env.prod의 change-me 값을 실제 운영 값으로 수정
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

| 구분 | 주소 |
| --- | --- |
| 웹 화면 | `http://<server-host>/` |
| API | `http://<server-host>/api` |

## 기본 사용법

1. 웹 화면에서 AWR 리포트를 업로드합니다.
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
| `prod` | Nginx 웹 배포. API/DB/Redis는 내부 네트워크 통신 | `80` |

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
- prod의 `change-me` 값은 운영 배포 전에 반드시 실제 Secret으로 교체합니다.
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
