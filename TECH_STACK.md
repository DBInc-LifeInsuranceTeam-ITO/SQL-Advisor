# SQLAdvisor 기술 스택

SQLAdvisor는 Oracle AWR 분석, 실시간 SQL 모니터링, Direct DB 기반 SQL 튜닝을 제공하는 웹 애플리케이션입니다.

## 전체 구성

| 영역 | 구성 | 주요 역할 |
| --- | --- | --- |
| Frontend | Vue 3 + Vite + TypeScript | 실시간 대시보드, AWR 화면, Advisor Chat, SQL 튜닝, 설정 |
| Backend API | Java 17 + Spring Boot | REST API, Oracle 모니터링, AWR 분석, SQL 튜닝, 인증, AI provider 연동 |
| Worker | Python 3.12 + FastAPI + RQ | PDF/HTML/TXT 텍스트 추출, OCR, 비동기 작업 |
| Database | PostgreSQL 16 + pgvector | 리포트, 분석 결과, 사용자, 연결정보, RAG chunk/vector 저장 |
| Queue | Redis 7 | Worker 작업 큐 |
| Deployment | Docker Compose + Nginx | 운영 컨테이너 구성, 정적 파일 서빙, API 프록시 |

## Frontend

| 항목 | 기술 |
| --- | --- |
| Framework | Vue 3 |
| Language | TypeScript |
| Build | Vite 7 |
| Routing | Vue Router 4 |
| State | Pinia |
| HTTP | Axios |
| Chart | SVG 기반 실시간 그래프, vue3-apexcharts |
| Web Server | Nginx 1.27 Alpine |

주요 화면:

- `/dashboard`: 실시간 Oracle SQL 대시보드
- `/sql-tuning`: Direct DB 및 직접 입력 SQL 튜닝
- `/upload`: AWR 업로드
- `/reports`: AWR 목록
- `/reports/:id`: AWR 상세
- `/chat`: Advisor Chat
- `/settings/ai`: AI 설정

주요 파일:

```text
frontend/src/views/awr/AwrDashboard.vue
frontend/src/views/awr/AwrDashboard.css
frontend/src/api/monitoring.ts
frontend/src/api/sqlTuning.ts
frontend/src/router/index.ts
```

## Backend API

| 항목 | 기술 |
| --- | --- |
| Runtime | Java 17 |
| Framework | Spring Boot 3.5.3 |
| Build | Gradle Wrapper 8.14.3 |
| REST | Spring Web |
| Authentication | Spring Security, Google API Client, 내부 AD 식별자 방식 |
| Validation | Jakarta Validation |
| DB Access | Spring JDBC, JdbcTemplate |
| Connection Pool | HikariCP |
| JSON | Jackson |
| HTML Parsing | jsoup |
| Test | Spring Boot Test, JUnit Platform, H2 |
| Container | Eclipse Temurin 17 |

### 실시간 모니터링

실시간 대시보드는 등록된 Oracle 연결을 사용해 현재 상태를 조회합니다.

수집 항목:

- Active SQL 수
- 30초 이상 장기 실행 SQL 수
- 주의 SQL 수
- Blocking 세션 수
- Active Sessions, CPU, I/O 활동 지표
- 부하 상위 SQL

주요 클래스:

```text
sqladvisor/src/main/java/dbinc/sqladvisor/domain/monitoring/controller/RealtimeMonitoringController.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/monitoring/dto/MonitoringDtos.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/monitoring/service/RealtimeMonitoringService.java
```

API:

```http
GET /api/monitoring/dashboard?connectionId={id}
```

프론트는 현재 2초 주기로 대시보드 API를 호출합니다. Top SQL은 조회 부하를 줄이기 위해 별도 주기로 호출합니다.

### SQL 튜닝

Direct DB 또는 직접 입력 정보를 사용합니다.

근거 데이터:

- SQL text
- 실행계획
- 기존 인덱스
- bind sample
- Buffer Gets, Disk Reads, Executions, Elapsed Time

주요 클래스:

```text
sqladvisor/src/main/java/dbinc/sqladvisor/domain/sqltuning/controller/SqlTuningController.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/sqltuning/controller/DirectDbSqlTuningController.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/sqltuning/controller/TargetDbConnectionController.java
```

## Worker

| 항목 | 기술 |
| --- | --- |
| Runtime | Python 3.12 |
| API | FastAPI |
| ASGI | Uvicorn |
| Queue | RQ + Redis |
| PDF | PyMuPDF, pdfplumber |
| OCR | Tesseract, pytesseract |
| HTML | BeautifulSoup4 |
| Image | Pillow |

Worker는 AWR 파일 텍스트 추출 작업을 처리하고 Backend API에 결과를 전달합니다.

## Data 및 RAG

| 항목 | 구성 |
| --- | --- |
| Database | PostgreSQL 16 |
| Vector Extension | pgvector |
| Vector Index | ivfflat, vector_cosine_ops |
| RAG Storage | `rag_chunk`, `VECTOR(1536)` |
| 주요 업무 테이블 | `awr_report`, `awr_section`, `awr_sql_metric`, `awr_wait_event`, `target_db_connection`, `sql_tuning_result` |

RAG는 AWR section, SQL metric, Wait Event를 chunk로 만들고 SQL_ID 우선 검색과 vector similarity 검색을 함께 사용합니다.

## AI Provider

| 구분 | 지원 범위 |
| --- | --- |
| Local | 규칙 기반 로컬 분석 |
| Chat/LLM | OpenAI, Gemini, 내부 OpenAI-compatible endpoint, Ollama |
| Embedding | OpenAI, Gemini, 내부 OpenAI-compatible endpoint, Ollama |
| 설정 항목 | Anthropic, xAI, Cohere rerank |

외부 LLM이 비활성화되거나 실패하면 로컬 Advisor 결과로 동작할 수 있습니다.

## 운영 배포

| 항목 | 기술 |
| --- | --- |
| Container | Docker |
| Orchestration | Docker Compose |
| Compose | `deploy/docker-compose.prod.yml` |
| Reverse Proxy | Nginx |
| Backend Image | Eclipse Temurin 17 |
| Frontend Build | Node 22 Alpine |
| Frontend Runtime | Nginx 1.27 Alpine |
| Worker | Python 3.12 Slim |
| Database | `pgvector/pgvector:pg16` |
| Queue | `redis:7-alpine` |

운영 배포 명령:

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

## 빌드 및 검증 명령

```bash
# Frontend
cd frontend
npm install
npm run build
npm run type-check
npm run lint

# Backend
cd sqladvisor
./gradlew test
```

## 운영 고려사항

- Oracle 모니터링 계정은 조회 전용 권한을 사용합니다.
- 실시간 조회 주기가 짧을수록 Oracle과 API 부하가 증가합니다.
- Direct DB 튜닝 결과는 운영 반영 전 테스트가 필요합니다.
- API Key와 DB 비밀번호는 환경 변수 또는 Secret으로 주입합니다.
