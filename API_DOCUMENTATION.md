# SQLAdvisor API 문서

## 1. 개요

SQLAdvisor API는 Spring Boot 기반 REST API입니다.

- Base URL: `/api`
- 응답 형식: JSON
- 기본 Content-Type: `application/json`
- 파일 업로드: `multipart/form-data`

대부분의 응답은 다음 구조를 사용합니다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {},
  "timestamp": "2026-08-06T15:00:00"
}
```

## 2. 실시간 DB 모니터링 API

### 대시보드 조회

```http
GET /api/monitoring/dashboard?connectionId={id}
```

등록된 Oracle 연결을 사용해 현재 DB 상태를 수집합니다.

#### Query Parameter

| 이름 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- |
| `connectionId` | long | yes | Target DB 연결 ID |

#### 주요 응답 데이터

```json
{
  "success": true,
  "data": {
    "connection": {
      "connectionId": 1,
      "name": "DBLIFE",
      "databaseType": "ORACLE",
      "collectedAt": "2026-08-06T15:00:00"
    },
    "summary": {
      "activeSqlCount": 3,
      "longRunningSqlCount": 1,
      "warningSqlCount": 2,
      "blockingSessionCount": 0
    },
    "activity": {
      "points": [
        {
          "collectedAt": "2026-08-06T14:59:58",
          "activeSessions": 3,
          "executions": 120,
          "cpu": 1.4,
          "io": 450
        }
      ]
    }
  }
}
```

#### 수집 항목

- 현재 Active SQL 수
- 30초 이상 장기 실행 SQL 수
- 주의 SQL 수
- Blocking 세션 수
- Active Sessions
- 실행 횟수
- CPU 관련 지표
- I/O 관련 지표
- 마지막 수집 시각

#### 오류

| 상황 | 응답 |
| --- | --- |
| 존재하지 않는 connectionId | `400 Bad Request` |
| 지원하지 않는 DB 유형 | `400 Bad Request` |
| Oracle 연결 실패 | `400 Bad Request` |
| 조회 권한 부족 | `400 Bad Request` 또는 DB 오류 메시지 |

프론트 대시보드는 현재 2초 주기로 이 API를 호출합니다.

## 3. Target DB 연결 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/db-connections` | 연결 목록 조회 |
| `POST` | `/api/db-connections` | 연결 저장 |
| `POST` | `/api/db-connections/test` | 연결 테스트 |
| `DELETE` | `/api/db-connections/{id}` | 연결 삭제 |

연결 정보에는 DB 유형, 이름, JDBC URL, 사용자, 비밀번호 등이 포함될 수 있습니다. 비밀번호는 응답에 원문으로 노출하지 않습니다.

## 4. Direct DB SQL 튜닝 API

### Top SQL 후보 조회

```http
GET /api/sql-tuning/direct/top-sql
```

주요 Query Parameter:

| 이름 | 설명 |
| --- | --- |
| `connectionId` | Target DB 연결 ID |
| `source` | `CURRENT` 등 조회 소스 |
| `limit` | 조회 건수 |
| `sortBy` | `ELAPSED`, `CPU`, `BUFFER_GETS`, `DISK_READS` 등 |
| `schema` | 스키마 필터 |
| `module` | 모듈 필터 |
| `program` | 프로그램 필터 |

### SQL_ID 근거 수집

```http
POST /api/sql-tuning/direct/context
```

수집 가능한 근거:

- SQL text
- 실행계획
- 기존 인덱스
- bind sample
- Elapsed Time
- CPU Time
- Buffer Gets
- Disk Reads
- Executions

### Direct DB 튜닝 실행

```http
POST /api/sql-tuning/direct
```

SQL_ID와 수집된 근거를 사용해 튜닝 결과를 생성합니다.

## 5. 직접 입력 SQL 튜닝 API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/sql-tuning` | SQL text 직접 입력 튜닝 |
| `GET` | `/api/sql-tuning/history` | 튜닝 히스토리 조회 |
| `GET` | `/api/sql-tuning/{id}` | 튜닝 결과 상세 조회 |

가능하면 실행계획, DDL, 인덱스, bind 정보를 함께 전달합니다.

## 6. AWR API

| Method | Path | 설명 |
| --- | --- | --- |
| `POST` | `/api/reports` | AWR 리포트 업로드 |
| `GET` | `/api/reports` | 리포트 목록 조회 |
| `GET` | `/api/reports/{id}` | 리포트 상세 조회 |
| `GET` | `/api/reports/{id}/status` | 처리 상태 조회 |
| `POST` | `/api/reports/{id}/analyze` | 병목 분석 실행 |
| `POST` | `/api/reports/{id}/chat` | Advisor Chat |
| `GET` | `/api/reports/{id}/chat/history` | Chat 히스토리 |
| `GET` | `/api/reports/{id}/sql` | SQL metric 목록 |
| `GET` | `/api/reports/{id}/sql/{sqlId}` | SQL_ID 상세 |
| `POST` | `/api/reports/{id}/sql/{sqlId}/tune` | AWR SQL_ID 튜닝 |

지원 파일:

- HTML/HTM
- TXT/LOG
- PDF

PDF는 Worker/OCR 상태에 따라 별도 텍스트 추출이 필요할 수 있습니다.

## 7. 인증 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/auth/config` | 인증 설정 조회 |
| `GET` | `/api/auth/me` | 현재 사용자 조회 |
| `POST` | `/api/auth/google` | Google 로그인 |
| `POST` | `/api/auth/internal` | 내부 AD 식별자 로그인 |
| `POST` | `/api/auth/local` | 로컬 식별자 로그인 |
| `POST` | `/api/auth/logout` | 로그아웃 |

지원 역할:

- `ADMIN`
- `USER`
- `MONITOR`

## 8. AI 설정 API

| Method | Path | 설명 |
| --- | --- | --- |
| `GET` | `/api/config/ai` | 현재 AI 설정 조회 |
| `POST` | `/api/config/ai` | AI 설정 저장 |
| `GET` | `/api/config/ai/models` | 지원 모델 목록 조회 |

지원되는 주요 provider:

- Local rule-based Advisor
- OpenAI
- Gemini
- 내부 OpenAI-compatible endpoint
- Ollama

## 9. 운영 및 보안 주의사항

- Oracle 모니터링 계정은 조회 전용 권한을 사용합니다.
- 실시간 조회 주기를 줄이면 Oracle 및 API 부하가 증가합니다.
- DB 비밀번호와 API Key는 로그와 응답에 노출하지 않습니다.
- AI 결과 및 SQL 튜닝 권고는 운영 반영 전 검증합니다.
- 운영 DB에서 부하 테스트용 Cartesian Join을 실행하지 않습니다.

## 10. 관련 소스

```text
frontend/src/api/monitoring.ts
frontend/src/api/sqlTuning.ts
sqladvisor/src/main/java/dbinc/sqladvisor/domain/monitoring/controller/RealtimeMonitoringController.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/monitoring/service/RealtimeMonitoringService.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/sqltuning/controller/DirectDbSqlTuningController.java
sqladvisor/src/main/java/dbinc/sqladvisor/domain/sqltuning/controller/SqlTuningController.java
```
