# SQLAdvisor Configuration Guide

SQLAdvisor 배포 설정은 `deploy/` 폴더에 모여 있으며, 실행 모드는 `dev`와 `prod`로 나뉩니다.

## Runtime Files

| File | Purpose |
| --- | --- |
| `deploy/docker-compose.dev.yml` | 로컬 개발/검증용 Compose |
| `deploy/docker-compose.prod.yml` | 운영 배포용 Compose |
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
cp deploy/.env.dev.example deploy/.env.dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```

| Service | URL |
| --- | --- |
| Web | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

## Prod

prod는 Nginx가 웹 정적 파일을 서빙하고 `/api` 요청을 내부 API 컨테이너로 프록시합니다. 호스트에는 Nginx `80` 포트만 노출합니다.

```bash
cp deploy/.env.prod.example deploy/.env.prod
# edit deploy/.env.prod and replace every change-me value
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

| Service | URL |
| --- | --- |
| Web | `http://<server-host>/` |
| API through Nginx | `http://<server-host>/api` |

## Required Prod Environment

`deploy/.env.prod`에는 최소한 다음 값을 운영 Secret으로 설정합니다.

```env
POSTGRES_PASSWORD=change-me
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/sqladvisor
SPRING_DATASOURCE_USERNAME=sqladvisor
SPRING_DATASOURCE_PASSWORD=change-me
DATABASE_URL=postgresql://sqladvisor:change-me@postgres:5432/sqladvisor
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
