# SQLAdvisor Configuration Guide

SQLAdvisor 배포 설정은 `deploy/` 폴더에 모여 있으며, 실행 모드는 `dev`와 `prod`로 나뉩니다.

## Runtime Files

| File | Purpose |
| --- | --- |
| `deploy/docker-compose.dev.yml` | 로컬 개발/검증용 Compose |
| `deploy/docker-compose.prod.yml` | 운영 배포용 Compose |
| `deploy/init-env.sh` | dev/prod env 초기화 및 필수값 검증 스크립트 |
| `deploy/.env.dev.example` | dev 환경 템플릿 |
| `deploy/.env.prod.example` | prod 환경 템플릿 |
| `deploy/.env.dev` | dev 실제 환경값, git 제외 |
| `deploy/.env.prod` | prod 실제 환경값, git 제외 |
| `deploy/config/application-dev.yml` | dev API profile |
| `deploy/config/application-prod.yml` | prod API profile |
| `deploy/config/application-test.yml` | test API profile |
| `deploy/config/logback-spring.xml` | API logging config |
| `deploy/nginx/prod.conf` | prod Nginx web deployment config |

## Dev

dev는 로컬 확인에 편하게 웹, API, PostgreSQL, Redis 포트를 모두 호스트에 노출합니다.

```bash
sh deploy/init-env.sh --mode dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```

| Service | URL |
| --- | --- |
| Web | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

## Prod

prod는 Nginx가 웹 정적 파일을 서빙하고 `/api` 요청을 내부 API 컨테이너로 프록시합니다. 호스트에는 Nginx `80` 포트와 외부 DB 클라이언트 접속용 PostgreSQL `5432` 포트를 노출합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

| Service | URL |
| --- | --- |
| Web | `http://<server-host>/` |
| API through Nginx | `http://<server-host>/api` |
| PostgreSQL | `<server-host>:5432` |

## Required Prod Environment

`deploy/init-env.sh --mode prod`는 DB 비밀번호를 생성하고 다음 값을 동기화합니다. 기존 `deploy/.env.prod`가 있으면 덮어쓰지 않고 누락된 키만 보강합니다.

```env
POSTGRES_PASSWORD=<generated-secret>
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/sqladvisor
SPRING_DATASOURCE_USERNAME=sqladvisor
SPRING_DATASOURCE_PASSWORD=<generated-secret>
DATABASE_URL=postgresql://sqladvisor:<generated-secret>@postgres:5432/sqladvisor
```

인증을 사용할 때는 외부용/내부용 모드를 선택합니다.

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=external
GOOGLE_CLIENT_ID=<Google OAuth Client ID>
```

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=internal
APP_INTERNAL_AUTH_EMAIL_DOMAIN=internal.local
```

## AI Provider Environment

외부 AI 없이 로컬 모드로 실행:

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

Supported key placeholders:

| Variable | Used For |
| --- | --- |
| `OPENAI_API_KEY` | OpenAI chat/embedding |
| `GEMINI_API_KEY` | Gemini chat/embedding |
| `ANTHROPIC_API_KEY` | Anthropic chat |
| `XAI_API_KEY` | xAI chat |
| `COHERE_API_KEY` | Cohere rerank |

## Git Safety

`deploy/.env.dev`와 `deploy/.env.prod`는 `.gitignore`에 포함되어 있습니다. 실제 API Key, DB 비밀번호, 운영 URL은 example 파일이나 YAML에 직접 쓰지 말고 실제 환경 파일 또는 배포 Secret으로 주입합니다.
