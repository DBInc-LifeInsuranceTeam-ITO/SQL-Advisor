# SQLAdvisor Deploy

SQLAdvisor는 운영용 Docker Compose 구성을 기준으로 배포합니다.

## 운영 배포

Nginx 컨테이너가 정적 프론트엔드를 서빙하고 `/api` 요청을 내부 API 컨테이너로 프록시합니다. 호스트에는 Nginx `80` 포트와 외부 DB 클라이언트 접속용 PostgreSQL `5432` 포트를 노출합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

운영 URL:

| Service | URL |
| --- | --- |
| Web + API proxy | `http://<server-host>/` |
| API through Nginx | `http://<server-host>/api` |
| PostgreSQL | `<server-host>:5432` |

## 운영 명령어

```bash
# 상태 확인
docker compose -f deploy/docker-compose.prod.yml ps

# 전체 로그
docker compose -f deploy/docker-compose.prod.yml logs -f

# 서비스별 로그
docker compose -f deploy/docker-compose.prod.yml logs -f web
docker compose -f deploy/docker-compose.prod.yml logs -f api
docker compose -f deploy/docker-compose.prod.yml logs -f worker

# 재빌드 및 기동
docker compose -f deploy/docker-compose.prod.yml up -d --build

# 종료
docker compose -f deploy/docker-compose.prod.yml down
```

데이터 볼륨까지 초기화하는 명령은 PostgreSQL 데이터를 삭제하므로 주의해서 사용합니다.

```bash
docker compose -f deploy/docker-compose.prod.yml down -v
```

## 운영 배포 파일

| File | Purpose |
| --- | --- |
| `init-env.sh` | 운영 env 초기화 및 필수값 검증 스크립트 |
| `docker-compose.prod.yml` | 운영 Docker Compose |
| `.env.prod.example` | 운영 환경 템플릿 |
| `.env.prod` | 운영 비밀값, Git 제외 |
| `config/application-prod.yml` | 운영 API 설정 |
| `config/logback-spring.xml` | API 로그 설정 |
| `nginx/prod.conf` | 운영 Nginx 웹 배포 설정 |

개발용 Compose와 설정 파일은 저장소에 유지하지만 현재 운영 절차 문서에서는 사용하지 않습니다.

## 보안 주의사항

- `.env.prod`는 Git에 커밋하지 않습니다.
- API Key, DB 비밀번호, 운영 URL은 Compose나 YAML에 직접 작성하지 않습니다.
- 운영 Oracle 연결 계정은 조회 전용 권한을 사용합니다.
- PostgreSQL `5432` 포트가 외부에 노출되는 경우 접근 대역을 방화벽으로 제한합니다.
