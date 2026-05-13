# SQLAdvisor 기술 스택

이 문서는 저장소의 설정 파일과 소스 구조를 기준으로 SQLAdvisor에서 사용하는 주요 기술 스택을 정리한 문서입니다.

## 전체 구성

SQLAdvisor는 Oracle AWR 리포트를 업로드하고, 텍스트 추출 및 구조화, SQL/Wait Event 분석, RAG 기반 Advisor Chat을 제공하는 웹 애플리케이션입니다.

| 영역 | 구성 | 주요 역할 |
| --- | --- | --- |
| Frontend | Vue 3 + Vite + TypeScript | 사용자 화면, 리포트 업로드/조회, 대시보드, Advisor Chat |
| Backend API | Java 17 + Spring Boot | REST API, AWR 파싱/분석, RAG 검색, AI provider 연동 |
| Worker | Python 3.12 + FastAPI + RQ | PDF/HTML/TXT 리포트 텍스트 추출, OCR, 비동기 작업 처리 |
| Database | PostgreSQL 16 + pgvector | AWR 구조화 데이터, 분석 결과, RAG chunk/vector 저장 |
| Queue | Redis 7 | Worker 비동기 작업 큐 |
| Deployment | Docker Compose + Nginx | dev/prod 컨테이너 구성, 정적 파일 서빙, API 프록시 |

## Frontend

| 항목 | 사용 기술 |
| --- | --- |
| Runtime/Build | Node.js 22, Vite 7 |
| Framework | Vue 3 |
| Language | TypeScript |
| Routing | Vue Router 4 |
| State | Pinia |
| HTTP Client | Axios |
| Chart | vue3-apexcharts |
| Lint/Type Check | ESLint 9, vue-tsc, TypeScript 5.8 |
| Web Server | Nginx 1.27 Alpine 컨테이너에서 정적 파일 서빙 |



- `frontend/package.json`
- `frontend/vite.config.ts`
- `frontend/tsconfig.app.json`
- `frontend/Dockerfile`
- `frontend/nginx.conf`

## Backend API

| 항목 | 사용 기술 |
| --- | --- |
| Language/Runtime | Java 17 |
| Framework | Spring Boot 3.5.3 |
| Build | Gradle Wrapper 8.14.3 |
| REST API | Spring Web |
| Validation | Spring Validation, Jakarta Validation |
| Database Access | Spring JDBC, JdbcTemplate |
| Connection Pool | HikariCP |
| JSON | Jackson |
| HTML Parsing | jsoup 1.17.2 |
| Boilerplate | Lombok |
| Test | Spring Boot Test, JUnit Platform, H2 |
| Container Runtime | Eclipse Temurin 17 JDK/JRE |

주요 API는 `/api` prefix를 사용하며, AWR 업로드, 리포트 조회, 분석, Chat, AI 설정 API를 제공합니다.



- `sqladvisor/build.gradle`
- `sqladvisor/gradle/wrapper/gradle-wrapper.properties`
- `sqladvisor/Dockerfile`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/controller/AwrReportController.java`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/service/AwrRepository.java`

## Worker

| 항목 | 사용 기술 |
| --- | --- |
| Language/Runtime | Python 3.12 |
| Web API | FastAPI 0.115.6 |
| ASGI Server | Uvicorn 0.34.0 |
| Queue | RQ 2.1.0, Redis client 5.2.1 |
| PDF Parsing | PyMuPDF, pdfplumber |
| OCR | Tesseract OCR, pytesseract |
| HTML Parsing | BeautifulSoup4 |
| Image Processing | Pillow |
| API Callback | requests |

Worker는 `/jobs/extract`로 작업을 큐에 넣고, `awr` 큐에서 리포트 텍스트 추출 작업을 수행한 뒤 Backend API의 internal callback으로 결과를 전달합니다.



- `worker/requirements.txt`
- `worker/Dockerfile`
- `worker/worker_app/main.py`
- `worker/worker_app/tasks.py`

## Data 및 RAG

| 항목 | 사용 기술 |
| --- | --- |
| Database | PostgreSQL 16 |
| Vector Extension | pgvector |
| Vector Index | ivfflat, vector_cosine_ops |
| Structured Storage | `awr_report`, `awr_section`, `awr_sql_metric`, `awr_wait_event` |
| RAG Storage | `rag_chunk` 테이블, `VECTOR(1536)` embedding |
| Analysis Storage | `analysis_result`, `feedback` |

RAG는 AWR section, SQL metric, wait event를 chunk로 만들고, embedding provider가 활성화된 경우 vector를 저장해 SQL_ID 우선 검색과 vector similarity 검색을 함께 사용합니다.



- `sqladvisor/init-db/postgres/001_pgvector.sql`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/service/AwrRagService.java`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/service/AwrRepository.java`
- `RAG_ARCHITECTURE.md`

## AI Provider 연동

| 구분 | 지원 provider |
| --- | --- |
| Local Advisor | 규칙 기반 로컬 분석 |
| Chat/LLM | OpenAI, Gemini, Ollama |
| Embedding | OpenAI, Gemini, Ollama |
| 설정 항목 존재 | Anthropic, xAI, Cohere rerank |

현재 실제 호출 구현은 OpenAI, Gemini, Ollama 중심이며, 외부 LLM이 비활성화되거나 호출에 실패하면 로컬 Advisor 결과로 동작합니다.



- `deploy/config/application-dev.yml`
- `deploy/config/application-prod.yml`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/service/AwrAiClient.java`
- `sqladvisor/src/main/java/dbinc/sqladvisor/domain/awr/service/AwrAiConfigService.java`

## Deployment

| 항목 | 사용 기술 |
| --- | --- |
| Container | Docker |
| Orchestration | Docker Compose |
| Dev Compose | `deploy/docker-compose.dev.yml` |
| Prod Compose | `deploy/docker-compose.prod.yml` |
| Reverse Proxy | Nginx |
| Backend Image | Eclipse Temurin 17 |
| Frontend Build Image | Node 22 Alpine |
| Frontend Runtime Image | Nginx 1.27 Alpine |
| Worker Image | Python 3.12 Slim |
| Database Image | `pgvector/pgvector:pg16` |
| Queue Image | `redis:7-alpine` |

dev 모드는 Web, API, PostgreSQL, Redis 포트를 로컬에 노출하고, prod 모드는 Nginx 80 포트만 외부에 노출하는 구성입니다.



- `deploy/docker-compose.dev.yml`
- `deploy/docker-compose.prod.yml`
- `deploy/nginx/prod.conf`
- `sqladvisor/Dockerfile`
- `frontend/Dockerfile`
- `worker/Dockerfile`

## 개발 및 검증 명령

| 영역 | 명령 |
| --- | --- |
| Frontend 개발 서버 | `npm run dev` |
| Frontend 빌드 | `npm run build` |
| Frontend 타입 체크 | `npm run type-check` |
| Frontend 린트 | `npm run lint` |
| Backend 테스트 | `./gradlew test` |
| Backend 실행 | `./gradlew bootRun` |
| Dev 전체 실행 | `docker compose -f deploy/docker-compose.dev.yml up -d --build` |
| Prod 전체 실행 | `docker compose -f deploy/docker-compose.prod.yml up -d --build` |

## 참고

- `frontend/dist`, `frontend/node_modules`, `sqladvisor/BOOT-INF`, `sqladvisor/libs`, `sqladvisor/logs`는 빌드 산출물 또는 로컬 의존성으로 보고 기술 스택 판단의 주 근거에서 제외했습니다.
- README 계열 문서 일부는 현재 인코딩이 깨져 보이므로, 버전과 의존성은 주로 `package.json`, `build.gradle`, `requirements.txt`, Docker/Compose/YAML 설정을 기준으로 정리했습니다.
