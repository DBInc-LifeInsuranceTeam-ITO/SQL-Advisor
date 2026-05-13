# SQLAdvisor API 문서

## 목차

1. [개요](#개요)
2. [공통 응답 구조](#공통-응답-구조)
3. [AWR 분석 API](#awr-분석-api)
4. [AI 설정 API](#ai-설정-api)
5. [주요 데이터 타입](#주요-데이터-타입)
6. [오류 응답](#오류-응답)
7. [기존 호환 API](#기존-호환-api)

---

## 개요

SQLAdvisor API는 Spring Boot 기반 REST API입니다. 현재 웹 프론트엔드는 AWR 리포트 업로드, 파싱 상태 조회, Top SQL/Wait Event 확인, 규칙 기반 Advisor 분석, Advisor Chat 기능을 사용합니다.

### 기본 정보

- Base URL: `/api`
- 응답 형식: JSON
- 기본 Content-Type: `application/json`
- 파일 업로드 Content-Type: `multipart/form-data`
- Charset: UTF-8

로컬 Docker Compose 실행 시 직접 API 주소는 다음과 같습니다.

```text
http://localhost:18080/api
```

프론트엔드에서는 `VITE_API_BASE_URL=/api`를 사용하며, 개발 서버 또는 Nginx가 API 서버로 프록시합니다.

---

## 공통 응답 구조

대부분의 JSON API는 `ApiResponse<T>` 형식으로 응답합니다.

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {},
  "timestamp": "2026-05-10T22:30:00"
}
```

### 필드 설명

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `success` | boolean | 요청 성공 여부 |
| `message` | string | 처리 메시지 |
| `data` | object, array, null | 실제 응답 데이터 |
| `errorCode` | string, null | 실패 시 오류 코드 |
| `timestamp` | string | 서버 응답 시각 |

프론트엔드의 `src/services/api.ts`는 `success=true` 응답의 `data`를 자동으로 unwrap합니다.

---

## AWR 분석 API

### 1. AWR 리포트 업로드

**POST** `/api/reports`

AWR 파일을 업로드하고 텍스트 추출, 섹션 파싱, SQL metric 추출을 실행합니다.

#### Request

| 이름 | 위치 | 타입 | 필수 | 설명 |
| --- | --- | --- | --- | --- |
| `file` | multipart part | file | yes | AWR HTML, TXT, LOG, PDF 파일 |

지원 확장자는 `.html`, `.htm`, `.txt`, `.log`, `.pdf`입니다.

```bash
curl -X POST http://localhost:18080/api/reports \
  -F "file=@awr-report.html"
```

#### Response 201

```json
{
  "success": true,
  "message": "리소스가 성공적으로 생성되었습니다.",
  "data": {
    "id": 1,
    "filename": "awr-report.html",
    "status": "INDEXED",
    "message": "AWR 텍스트 추출, 섹션 파싱, SQL metric 구조화가 완료되었습니다."
  },
  "timestamp": "2026-05-10T22:30:00"
}
```

#### 상태값

| 상태 | 의미 |
| --- | --- |
| `INDEXED` | HTML/TXT 텍스트 추출과 구조화가 완료됐습니다. |
| `NEEDS_TEXT_EXTRACTION` | PDF 등에서 별도 OCR/PDF worker adapter가 필요합니다. |

---

### 2. AWR 리포트 목록 조회

**GET** `/api/reports`

업로드된 리포트 목록을 최신순으로 조회합니다.

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "id": 1,
      "filename": "awr-report.html",
      "dbName": "ORCL",
      "instanceName": "orcl1",
      "snapBegin": "2026-05-10 10:00",
      "snapEnd": "2026-05-10 11:00",
      "elapsedTime": "60.0 mins",
      "dbTime": "245.1 mins",
      "status": "INDEXED",
      "uploadedAt": "2026-05-10T22:30:00",
      "sectionCount": 18,
      "topSqlCount": 42,
      "waitEventCount": 8
    }
  ],
  "timestamp": "2026-05-10T22:30:01"
}
```

현재 이 목록은 API 서버 메모리에 저장됩니다. 서버 재시작 후에는 초기화될 수 있습니다.

---

### 3. AWR 리포트 상세 조회

**GET** `/api/reports/{reportId}`

선택한 리포트의 header, raw preview, 섹션, Top SQL, Top Wait Event, 최신 분석 결과를 조회합니다.

#### Path Parameters

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `reportId` | long | 리포트 ID |

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "id": 1,
    "filename": "awr-report.html",
    "dbName": "ORCL",
    "instanceName": "orcl1",
    "snapBegin": "2026-05-10 10:00",
    "snapEnd": "2026-05-10 11:00",
    "elapsedTime": "60.0 mins",
    "dbTime": "245.1 mins",
    "status": "INDEXED",
    "uploadedAt": "2026-05-10T22:30:00",
    "rawTextPreview": "Report Header...",
    "sections": [
      {
        "sectionName": "SQL ordered by Elapsed Time",
        "sectionOrder": 8,
        "rawText": "SQL ordered by Elapsed Time...",
        "parsedJson": {
          "chunkType": "sql",
          "source": "awr-parser",
          "lineCount": 30
        }
      }
    ],
    "topSql": [],
    "topWaitEvents": [],
    "latestAnalysis": null
  },
  "timestamp": "2026-05-10T22:30:02"
}
```

`topSql`은 상세 응답에서 최대 20개까지 반환됩니다.

---

### 4. AWR 리포트 처리 상태 조회

**GET** `/api/reports/{reportId}/status`

리포트 처리 진행률과 완료 단계를 조회합니다.

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "reportId": 1,
    "status": "INDEXED",
    "progress": 100,
    "currentStep": "분석 준비 완료",
    "completedSteps": [
      "file-upload",
      "text-extraction",
      "awr-section-parser",
      "sql-metric-extractor",
      "rag-chunk-ready"
    ],
    "warnings": [
      "HTML table/text extraction completed"
    ]
  },
  "timestamp": "2026-05-10T22:30:03"
}
```

---

### 5. 병목 분석 실행

**POST** `/api/reports/{reportId}/analyze`

선택한 리포트의 SQL metric과 wait event를 기반으로 규칙 기반 분석 결과를 생성합니다.

#### Request Body

```json
{
  "question": "이 AWR에서 제일 먼저 봐야 할 SQL과 병목을 분석해줘",
  "modelProvider": "local-rule-advisor"
}
```

`request` body는 생략할 수 있습니다. 질문이 없으면 기본 분석 질문이 사용됩니다.

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "analysisId": 1,
    "reportId": 1,
    "question": "이 AWR에서 제일 먼저 봐야 할 SQL과 병목을 분석해줘",
    "summary": "우선순위 1순위는 SQL_ID abc123def4567입니다.",
    "topFindings": [
      {
        "priority": 1,
        "sqlId": "abc123def4567",
        "symptom": "SQL ordered by Elapsed Time 상위 SQL",
        "evidence": [
          "section=SQL ordered by Elapsed Time",
          "rank_no=1",
          "elapsed_time_sec=120.5"
        ],
        "likelyCauses": [
          "AWR 상위 SQL에 포함되어 있어 execution plan, row estimate, wait profile 검증이 필요합니다."
        ],
        "recommendedActions": [
          "DBMS_XPLAN.DISPLAY_CURSOR로 actual plan과 predicate/access path를 확인합니다."
        ],
        "validationSteps": [
          "SELECT * FROM TABLE(DBMS_XPLAN.DISPLAY_CURSOR('abc123def4567', NULL, 'ALLSTATS LAST +PEEKED_BINDS'));"
        ],
        "risk": "인덱스 추가나 plan 고정은 DML 부하, 저장공간, plan regression을 유발할 수 있으므로 검증 후 적용해야 합니다.",
        "confidence": "medium"
      }
    ],
    "missingInputs": [
      "SQL execution plan with actual row statistics",
      "table/index DDL",
      "object statistics and last_analyzed",
      "bind variable samples",
      "ASH or SQL Monitor evidence for the same snapshot"
    ],
    "citations": [
      "SQL ordered by Elapsed Time / SQL_ID abc123def4567 / rank 1"
    ],
    "model": "rule-based-local-advisor",
    "createdAt": "2026-05-10T22:31:00"
  },
  "timestamp": "2026-05-10T22:31:00"
}
```

---

### 6. Advisor Chat

**POST** `/api/reports/{reportId}/chat`

선택한 리포트의 구조화 데이터만 사용해 질문에 답합니다.

#### Request Body

```json
{
  "question": "CPU 병목인지 I/O 병목인지 판단해줘",
  "modelProvider": "local-rule-advisor"
}
```

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "reportId": 1,
    "question": "CPU 병목인지 I/O 병목인지 판단해줘",
    "answer": "CPU 관점 Top SQL은 abc123def4567이며 section=SQL ordered by CPU Time...",
    "citations": [
      "SQL ordered by CPU Time / SQL_ID abc123def4567 / rank 1"
    ],
    "evidenceSql": [],
    "evidenceWaitEvents": [],
    "confidence": "medium"
  },
  "timestamp": "2026-05-10T22:32:00"
}
```

질문에 13자리 SQL_ID가 포함되면 해당 SQL_ID metric을 우선 근거로 사용합니다.

---

### 7. SQL metric 목록 조회

**GET** `/api/reports/{reportId}/sql`

리포트에서 추출된 SQL metric 전체를 조회합니다.

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": [
    {
      "sqlId": "abc123def4567",
      "sectionName": "SQL ordered by Elapsed Time",
      "rankNo": 1,
      "elapsedTimeSec": 120.5,
      "cpuTimeSec": 80.2,
      "bufferGets": 1000000,
      "diskReads": 12000,
      "executions": 15,
      "rowsProcessed": 300,
      "planHashValue": 987654321,
      "module": null,
      "sqlText": "select ...",
      "score": 98.4,
      "interpretationHint": "elapsed time 기준 상위 SQL"
    }
  ],
  "timestamp": "2026-05-10T22:32:30"
}
```

---

### 8. SQL_ID 단건 조회

**GET** `/api/reports/{reportId}/sql/{sqlId}`

특정 SQL_ID의 metric을 조회합니다.

#### Response 200

응답의 `data`는 `SqlMetricResponse` 단건입니다.

없는 SQL_ID를 요청하면 `400 Bad Request`로 응답합니다.

---

### 9. 분석 결과 단건 조회

**GET** `/api/analysis/{analysisId}`

이미 생성된 분석 결과를 analysis ID로 조회합니다.

#### Response 200

응답의 `data`는 `AnalysisResponse`입니다.

없는 analysis ID를 요청하면 `400 Bad Request`로 응답합니다.

---

## AI 설정 API

### AI provider 설정 상태 조회

**GET** `/api/config/ai`

현재 LLM provider, embedding provider, API key 설정 상태를 조회합니다.

#### Response 200

```json
{
  "success": true,
  "message": "요청이 성공적으로 처리되었습니다.",
  "data": {
    "llmProvider": "local",
    "embeddingProvider": "none",
    "chatModel": "rule-based-local-advisor",
    "embeddingModel": "none",
    "externalLlmEnabled": false,
    "llmApiKeyConfigured": true,
    "embeddingApiKeyConfigured": true,
    "configuredProviders": [],
    "missingProviderKeys": []
  },
  "timestamp": "2026-05-10T22:33:00"
}
```

현재 외부 provider 값은 구성 상태 확인에 사용됩니다. Advisor 답변은 기본적으로 local rule 기반입니다.

---

## 주요 데이터 타입

### UploadResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `id` | long | 리포트 ID |
| `filename` | string | 업로드 파일명 |
| `status` | string | `INDEXED`, `NEEDS_TEXT_EXTRACTION` |
| `message` | string | 처리 결과 메시지 |

### ReportSummaryResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `id` | long | 리포트 ID |
| `filename` | string | 업로드 파일명 |
| `dbName` | string | DB 이름 |
| `instanceName` | string | Instance 이름 |
| `snapBegin` | string, null | snapshot 시작 |
| `snapEnd` | string, null | snapshot 종료 |
| `elapsedTime` | string, null | elapsed time |
| `dbTime` | string, null | DB time |
| `status` | string | 처리 상태 |
| `uploadedAt` | string | 업로드 시각 |
| `sectionCount` | number | 추출된 섹션 개수 |
| `topSqlCount` | number | 추출된 SQL metric 개수 |
| `waitEventCount` | number | 추출된 wait event 개수 |

### StatusResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `reportId` | long | 리포트 ID |
| `status` | string | 처리 상태 |
| `progress` | number | 진행률 |
| `currentStep` | string | 현재 단계 |
| `completedSteps` | string[] | 완료된 단계 |
| `warnings` | string[] | 처리 경고 |

### SqlMetricResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `sqlId` | string | SQL_ID |
| `sectionName` | string | 추출 출처 섹션 |
| `rankNo` | number | 섹션 내 순위 |
| `elapsedTimeSec` | number, null | elapsed time |
| `cpuTimeSec` | number, null | CPU time |
| `bufferGets` | number, null | buffer gets |
| `diskReads` | number, null | disk reads |
| `executions` | number, null | executions |
| `rowsProcessed` | number, null | rows processed |
| `planHashValue` | number, null | plan hash value |
| `module` | string, null | module |
| `sqlText` | string, null | SQL text preview |
| `score` | number, null | Advisor 우선순위 점수 |
| `interpretationHint` | string | 해석 힌트 |

### WaitEventResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `waitClass` | string | wait class |
| `eventName` | string | wait event 이름 |
| `totalWaitTimeSec` | number, null | 총 대기 시간 |
| `avgWaitMs` | number, null | 평균 대기 시간 |
| `dbTimePercent` | number, null | DB Time 비율 |

### AnalysisResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `analysisId` | long | 분석 결과 ID |
| `reportId` | long | 리포트 ID |
| `question` | string | 분석 질문 |
| `summary` | string | 요약 |
| `topFindings` | FindingResponse[] | 우선순위별 finding |
| `missingInputs` | string[] | 추가로 필요한 검증 자료 |
| `citations` | string[] | AWR 근거 citation |
| `model` | string | 분석 모델명 |
| `createdAt` | string | 분석 생성 시각 |

### ChatResponse

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `reportId` | long | 리포트 ID |
| `question` | string | 질문 |
| `answer` | string | 답변 |
| `citations` | string[] | 근거 citation |
| `evidenceSql` | SqlMetricResponse[] | 답변에 사용된 SQL 근거 |
| `evidenceWaitEvents` | WaitEventResponse[] | 답변에 사용된 wait event 근거 |
| `confidence` | string | 신뢰도 |

---

## 오류 응답

잘못된 요청, 존재하지 않는 report ID, 존재하지 않는 SQL_ID 등은 `success=false` 응답으로 반환됩니다.

```json
{
  "success": false,
  "message": "AWR 리포트를 찾을 수 없습니다: 999",
  "errorCode": "BAD_REQUEST",
  "timestamp": "2026-05-10T22:34:00"
}
```

### 주요 오류 상황

| HTTP Status | 상황 |
| --- | --- |
| `400 Bad Request` | 파일 없음, 지원되지 않는 요청, 없는 report ID 또는 SQL_ID |
| `404 Not Found` | 라우팅되지 않은 API |
| `500 Internal Server Error` | 서버 내부 오류 |

---

## 기존 호환 API

백엔드에는 기존 인증서/라이선스, 알림, 관리자 기능 API가 남아 있습니다. 현재 AWR 중심 프론트엔드는 아래 API를 직접 사용하지 않습니다.

| 영역 | Base Path | 비고 |
| --- | --- | --- |
| 인증서/라이선스 관리 | `/api/certifications` | 기존 만료 관리 기능 |
| 사용자 정보 | `/api/user` | 권한/사용자 정보 |
| 업무명 관리 | `/api/biznames` | 기존 업무명 CRUD |
| 알림 관리 | `/api/notifications` | 기존 알림 확인/관리 |
| Failed SSL | `/api/failed-ssl` | 기존 SSL 확인 실패 관리 |
| 설정 조회 | `/api/config/auth` | 기존 권한 설정 조회 |
| Super Admin | `/api/super-admin` | 기존 관리자 기능 |
| 관리자 권한 | `/api/admin/authorization` | 부서/사번 권한 설정 |
| Invalid SSL | `/api/admin/ssl` | 잘못된 SSL 인증서 관리 |
| 알림 테스트 | `/api/notification-test` | 개발/테스트용 알림 트리거 |

이 API들을 계속 운영해야 한다면 기존 권한, SMS DB, 인증서 알림 스케줄러 설정도 함께 관리해야 합니다. AWR 전용 배포라면 해당 스케줄러와 알림 설정은 비활성화하는 것을 권장합니다.

---

이 문서는 2026년 5월 13일 기준 SQLAdvisor AWR 프론트엔드와 `AwrReportController`의 현재 API를 기준으로 작성되었습니다.
