# SQLAdvisor Deploy

SQLAdvisor 배포 구성은 `dev`와 `prod` 두 가지입니다.

## Dev 배포

로컬 개발/검증용입니다. API, 웹, PostgreSQL, Redis 포트를 모두 호스트에 노출합니다.

```bash
sh deploy/init-env.sh --mode dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```


Dev URL:

| Service | URL |
| --- | --- |
| Web | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

## Prod 배포

운영 배포용입니다. 웹은 Nginx 컨테이너가 정적 파일을 서빙하고 `/api` 요청을 내부 API 컨테이너로 프록시합니다. 호스트에는 Nginx `80` 포트와 외부 DB 클라이언트 접속용 PostgreSQL `5432` 포트를 노출합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```


Prod URL:

| Service | URL |
| --- | --- |
| Web + API proxy | `http://<server-host>/` |
| API through Nginx | `http://<server-host>/api` |
| PostgreSQL | `<server-host>:5432` |

## Common Commands

dev와 prod는 같은 컨테이너/볼륨 이름을 사용합니다. 모드를 바꿀 때는 실행 중인 모드를 먼저 내린 뒤 다른 모드를 올립니다.

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

데이터까지 초기화:

```bash
docker compose -f deploy/docker-compose.dev.yml down -v
docker compose -f deploy/docker-compose.prod.yml down -v
```

## Files

| File | Purpose |
| --- | --- |
| `init-env.sh` | dev/prod env 초기화 및 필수값 검증 스크립트 |
| `docker-compose.dev.yml` | 로컬 개발/검증용 Compose |
| `docker-compose.prod.yml` | 운영 배포용 Compose |
| `.env.dev.example` | dev 환경 템플릿 |
| `.env.prod.example` | prod 환경 템플릿 |
| `.env.dev` | dev 비밀값, git 제외 |
| `.env.prod` | prod 비밀값, git 제외 |
| `config/application-dev.yml` | dev API 설정 |
| `config/application-prod.yml` | prod API 설정 |
| `config/logback-spring.xml` | API 로그 설정 |
| `nginx/prod.conf` | 운영 Nginx 웹 배포 설정 |
