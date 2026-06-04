# SQLAdvisor 사용자 매뉴얼

## 목차

1. [시스템 개요](#시스템-개요)
2. [사용 전 준비](#사용-전-준비)
3. [화면별 사용법](#화면별-사용법)
4. [분석 결과 읽는 법](#분석-결과-읽는-법)
5. [자주 사용하는 흐름](#자주-사용하는-흐름)
6. [FAQ](#faq)
7. [지원 및 문의](#지원-및-문의)
8. [업데이트 이력](#업데이트-이력)

---

## 시스템 개요

SQLAdvisor는 Oracle AWR 리포트를 기반으로 SQL 성능 병목을 빠르게 파악하기 위한 웹 도구입니다. AWR 파일을 업로드하면 시스템이 주요 섹션을 추출하고, Top SQL, Wait Event, DB Time 관련 근거를 구조화해 튜닝 우선순위를 제안합니다.

### 주요 기능

- AWR HTML/TXT/PDF 파일 업로드
- 업로드된 리포트의 DB, Instance, snapshot, 처리 상태 확인
- SQL ordered by 섹션에서 Top SQL metric 추출
- Foreground Wait Event 기반 대기 이벤트 요약
- 규칙 기반 Advisor 분석으로 우선 확인할 SQL_ID, 근거, 원인 가설, 권장 조치, 검증 방법 제공
- 선택한 AWR 리포트의 metric과 wait event를 근거로 답변하는 Advisor Chat
- AI provider와 API key 설정 상태 확인

### 현재 지원 범위

| 항목 | 지원 상태 | 설명 |
| --- | --- | --- |
| AWR HTML | 지원 | HTML table/text를 추출해 즉시 구조화합니다. |
| AWR TXT/LOG | 지원 | 텍스트를 그대로 파싱해 섹션과 metric을 추출합니다. |
| AWR PDF | 부분 지원 | 파일 저장과 상태 표시는 가능하지만, 구조화 파싱에는 별도 OCR/PDF worker adapter가 필요합니다. |
| LLM 연동 | 지원 | 설정된 provider가 있으면 Advisor 분석/Chat에 사용하고, 없거나 실패하면 로컬 규칙 기반 Advisor로 동작합니다. |
| 데이터 영속성 | 지원 | 리포트, 분석 결과, RAG chunk, 사용자 정보는 PostgreSQL에 저장됩니다. |

---

## 사용 전 준비

### 접속 주소

운영 환경에서는 관리자가 제공한 SQLAdvisor URL로 접속합니다.

개발 또는 Docker Compose 환경의 기본 주소는 다음과 같습니다.

| 서비스 | 주소 |
| --- | --- |
| Web | `http://localhost:5173` |
| API | `http://localhost:18080/api` |

### 로그인

배포 설정에 따라 로그인 방식이 달라집니다.

- 외부용은 Google 로그인 화면으로 이동합니다.
- 내부용은 내부 포털이나 프록시가 전달하는 AD 계정 식별자(`loginEno`)로 로그인합니다.
- 로그인되어 있지 않으면 사이드패널에는 대시보드만 표시되고, 사용자 영역에는 `사용자 정보없음`이 표시됩니다.

### 권장 파일

가장 안정적인 분석을 위해 Oracle에서 생성한 AWR HTML 또는 TXT 리포트를 업로드하세요. PDF는 현재 OCR worker가 연결되지 않은 경우 `NEEDS_TEXT_EXTRACTION` 상태로 남을 수 있습니다.

### 업로드 전 확인사항

- 리포트가 해당 장애 또는 성능 저하 시간대의 snapshot을 포함하는지 확인합니다.
- 비교가 필요한 경우 정상 시간대와 장애 시간대 리포트를 각각 업로드합니다.
- SQL_ID 단위로 질문하려면 AWR에 SQL ordered by Elapsed Time, CPU Time, Gets, Reads 등의 섹션이 포함되어 있어야 합니다.

---

## 화면별 사용법

### 1. 대시보드

경로: `/dashboard`

대시보드는 전체 AWR 업로드 현황과 분석 가능 상태를 한눈에 보는 첫 화면입니다.

#### 확인할 수 있는 정보

- Reports: 현재 등록된 AWR 리포트 수
- Top SQL Rows: 구조화된 SQL metric 총 개수
- Wait Events: 추출된 wait event 총 개수
- Ready: `INDEXED` 상태로 분석 가능한 리포트 수
- 최근 리포트 목록
- AI 설정 상태

#### 사용 방법

1. 상단의 `AWR 업로드` 버튼으로 새 리포트를 등록합니다.
2. `Chat` 버튼으로 Advisor Chat 화면으로 이동합니다.
3. 최근 리포트의 파일명을 클릭해 상세 분석 화면으로 이동합니다.
4. AI 설정 영역에서 현재 LLM provider, embedding provider, 누락된 API key 여부를 확인합니다.

---

### 2. AWR 업로드

경로: `/upload`

AWR 파일을 시스템에 등록하고 파싱을 시작하는 화면입니다.

#### 업로드 방법

1. `파일 등록` 영역에서 AWR 파일을 선택합니다.
2. 지원 확장자는 `.html`, `.htm`, `.txt`, `.log`, `.pdf`입니다.
3. `분석 시작` 버튼을 클릭합니다.
4. 업로드 결과 영역에서 처리 상태와 메시지를 확인합니다.
5. `분석 결과 보기`를 클릭하면 리포트 상세 화면으로 이동합니다.
6. `Chat 열기`를 클릭하면 해당 리포트를 선택한 상태로 Advisor Chat을 시작합니다.

#### 처리 상태

| 상태 | 의미 | 다음 행동 |
| --- | --- | --- |
| `INDEXED` | 텍스트 추출, 섹션 파싱, SQL metric 구조화가 완료되었습니다. | 상세 화면에서 병목 분석 또는 Chat을 실행합니다. |
| `NEEDS_TEXT_EXTRACTION` | 파일은 저장됐지만 텍스트 추출 worker가 필요합니다. 주로 PDF에서 발생합니다. | 가능하면 HTML/TXT 리포트를 다시 업로드하거나 OCR/PDF worker 연결을 요청합니다. |

---

### 3. AWR 리포트 목록

경로: `/reports`

업로드된 AWR 리포트의 목록과 요약 지표를 확인하는 화면입니다.

#### 확인할 수 있는 정보

- ID
- 파일명
- DB / Instance
- Snapshot 시작/종료
- Status
- 추출된 SQL metric 개수
- 추출된 Wait Event 개수
- 업로드 일시

#### 사용 방법

1. `새로고침` 버튼으로 최신 목록을 다시 불러옵니다.
2. 파일명을 클릭해 리포트 상세 화면으로 이동합니다.
3. `Status`가 `INDEXED`인지 확인합니다. `INDEXED`가 아니면 분석 결과가 제한될 수 있습니다.

---

### 4. 리포트 상세

경로: `/reports/:id`

선택한 AWR 리포트의 파싱 상태와 분석 근거를 확인하는 핵심 화면입니다.

#### 상단 KPI

- Status: 현재 처리 상태
- DB Time: AWR header에서 추출한 DB Time
- Top SQL: 상세 화면에 표시되는 상위 SQL metric 개수
- Wait Events: 추출된 wait event 개수

#### 처리 상태 영역

처리 상태 영역에서는 진행률, 현재 단계, 완료된 단계, 경고 메시지를 확인할 수 있습니다.

완료 단계 예시는 다음과 같습니다.

- `file-upload`: 파일 업로드 완료
- `text-extraction`: 텍스트 추출 완료
- `awr-section-parser`: AWR 섹션 분리 완료
- `sql-metric-extractor`: SQL metric 추출 완료
- `rag-chunk-ready`: 분석 근거 후보 준비 완료

#### 병목 분석 실행

1. 상세 화면 오른쪽 상단의 `병목 분석` 버튼을 클릭합니다.
2. 분석이 완료되면 `튜닝 우선순위` 영역이 표시됩니다.
3. 우선순위별 SQL_ID, 근거, 원인 가설, 권장 조치, 검증 방법을 확인합니다.

#### Top SQL 확인

Top SQL 표에서는 SQL_ID별 주요 metric을 확인합니다.

- Section: SQL이 등장한 AWR 섹션
- Elapsed: 경과 시간 기준 지표
- CPU: CPU 시간 기준 지표
- Gets: buffer gets
- Reads: disk reads
- Execs: executions

SQL_ID를 클릭하면 해당 SQL_ID 질문이 입력된 Advisor Chat으로 이동합니다.

#### Top Wait Events 확인

Top Wait Events 영역에서는 wait event 이름, wait class, DB Time 비율, total wait time을 확인합니다. SQL metric만으로 병목을 단정하지 말고 wait event와 함께 해석해야 합니다.

#### 근거 섹션 확인

`근거 섹션` 영역에는 parser가 분리한 AWR 원문 섹션이 표시됩니다. 분석 결과가 예상과 다르면 해당 섹션에 필요한 원문이 추출됐는지 먼저 확인하세요.

---

### 5. Advisor Chat

경로: `/chat` 또는 `/reports/:id/chat`

Advisor Chat은 선택한 AWR 리포트에서 추출된 SQL metric과 wait event만을 근거로 튜닝 질문에 답합니다.

#### 질문 방법

1. 왼쪽 `리포트 선택`에서 분석할 리포트를 고릅니다.
2. 기본 질문 버튼을 선택하거나 직접 질문을 입력합니다.
3. `질문하기` 버튼을 클릭합니다.
4. 답변, confidence, citation, 근거 SQL 표를 확인합니다.

#### 기본 질문 예시

- 이 AWR에서 제일 먼저 봐야 할 SQL은?
- CPU 병목인지 I/O 병목인지 판단해줘
- Top Wait Event 기준으로 원인을 설명해줘
- SQL_ID `abc123def4567`의 문제 원인은?

#### 답변 해석 주의사항

Advisor Chat은 AWR에서 추출된 구조화 데이터만 사용합니다. 실행계획, bind 값, 테이블/인덱스 DDL, 최신 통계정보, ASH, SQL Monitor가 없으면 원인 판단은 가설 수준입니다. 운영 반영 전에는 반드시 검증 쿼리와 테스트 환경 확인이 필요합니다.

---

## 분석 결과 읽는 법

### 튜닝 우선순위

`튜닝 우선순위`는 상위 SQL metric을 점수화해 먼저 확인할 SQL_ID를 제안합니다. 우선순위가 높다고 바로 인덱스 추가나 plan 고정을 적용하라는 뜻은 아닙니다. 먼저 실제 실행계획과 통계정보를 확인해야 합니다.

### Finding 구성

| 항목 | 의미 |
| --- | --- |
| SQL_ID | 분석 대상 SQL 식별자 |
| 근거 | AWR에서 추출한 section, rank, elapsed, CPU, buffer gets, disk reads 등 |
| 가설 | 높은 metric을 기반으로 한 가능한 원인 |
| 권장 조치 | 확인하거나 실험해볼 튜닝 방향 |
| 검증 | DBMS_XPLAN, DBA_HIST_SQLSTAT, 통계정보 확인 등 실제 검증 절차 |
| Risk | 변경 적용 시 주의해야 할 부작용 |
| Confidence | 현재 AWR 근거만으로 판단 가능한 신뢰 수준 |

### 자주 보는 지표

- Elapsed Time: 사용자가 체감한 전체 수행 시간과 가장 가깝습니다.
- CPU Time: CPU-bound SQL인지 확인할 때 봅니다.
- Buffer Gets: logical I/O가 많아 비효율적인 조인, 낮은 선택도 조건, 인덱스 부재를 의심할 수 있습니다.
- Disk Reads: physical I/O, full scan, partition pruning 실패, storage latency를 의심할 수 있습니다.
- Executions: 반복 호출, bind 사용 여부, parse call 증가를 확인할 때 봅니다.
- Wait Event: SQL metric만으로 설명되지 않는 I/O, concurrency, commit, network 병목을 확인할 때 봅니다.

---

## 자주 사용하는 흐름

### 장애 시간대 AWR 빠르게 점검하기

1. `/upload`에서 장애 시간대 AWR HTML 또는 TXT를 업로드합니다.
2. 업로드 결과가 `INDEXED`인지 확인합니다.
3. `/reports/:id` 상세 화면에서 DB Time, Top SQL, Top Wait Events를 확인합니다.
4. `병목 분석`을 실행합니다.
5. 우선순위 1번 SQL_ID의 근거와 검증 SQL을 확인합니다.
6. 필요한 경우 `Chat`에서 CPU, I/O, wait 관점 질문을 이어갑니다.

### 특정 SQL_ID 확인하기

1. 리포트 상세 화면의 Top SQL 표에서 SQL_ID를 찾습니다.
2. SQL_ID를 클릭해 Chat으로 이동합니다.
3. 자동 입력된 질문을 실행합니다.
4. 답변의 citation과 metric을 확인합니다.
5. DBMS_XPLAN과 DBA_HIST_SQLSTAT에서 실제 plan, row estimate, 과거 snapshot 변화를 검증합니다.

### PDF 리포트가 분석되지 않을 때

1. 업로드 결과가 `NEEDS_TEXT_EXTRACTION`인지 확인합니다.
2. 가능하면 같은 AWR을 HTML 또는 TXT로 다시 생성해 업로드합니다.
3. PDF만 있는 경우 관리자에게 OCR/PDF worker adapter 연결 여부를 문의합니다.

---

## FAQ

### Q. 리포트를 업로드했는데 목록이 비어 있습니다.

A. 로그인 상태라면 공유 범위와 사용자 권한을 먼저 확인하세요. 비공개 리포트는 업로드한 사용자와 관리자만 볼 수 있습니다. 그래도 보이지 않으면 API 서버 로그와 PostgreSQL 연결 상태를 확인해야 합니다.

### Q. PDF 파일을 올렸는데 분석 결과가 거의 없습니다.

A. 현재 PDF는 텍스트 추출 dependency 또는 OCR worker가 연결돼야 구조화 파싱이 가능합니다. HTML 또는 TXT 형식의 AWR을 업로드하는 것이 가장 안정적입니다.

### Q. Top SQL이 보이지 않습니다.

A. AWR 원문에 `SQL ordered by Elapsed Time`, `SQL ordered by CPU Time`, `SQL ordered by Gets`, `SQL ordered by Reads` 등의 섹션이 없거나 parser가 인식하지 못한 경우입니다. `근거 섹션`에서 원문이 추출됐는지 확인하세요.

### Q. Chat 답변이 실제 원인이라고 확정해도 되나요?

A. 아니요. Chat 답변은 AWR metric 기반 가설입니다. 운영 변경 전에는 실행계획, object statistics, bind 값, ASH 또는 SQL Monitor 근거로 검증해야 합니다.

### Q. 외부 LLM API key를 꼭 설정해야 하나요?

A. 필수는 아닙니다. 기본 로컬 규칙 기반 Advisor는 외부 API key 없이 동작합니다. 외부 또는 내부 LLM provider를 설정하면 Advisor 분석/Chat에서 해당 provider를 사용할 수 있습니다.

### Q. 분석 결과를 다른 사람과 공유하려면 어떻게 하나요?

A. 현재 별도 내보내기 기능은 없습니다. 상세 화면의 SQL_ID, Finding, 검증 SQL, citation을 기준으로 공유 문서를 작성하세요.

### Q. 데이터가 민감한데 업로드해도 되나요?

A. 운영 정책에 따라야 합니다. AWR에는 SQL text, schema명, object명, host/instance 정보가 포함될 수 있습니다. 민감정보 반출 정책을 먼저 확인하세요.

---

## 지원 및 문의



### 장애 또는 데이터 오류

- 업로드 실패: 파일 형식, 크기, API 서버 상태를 확인한 뒤 관리자에게 문의
- 분석 결과 오류: 사용한 AWR 파일, report ID, 예상과 다른 화면 캡처 또는 오류 메시지를 함께 전달
- 리포트/분석 데이터 누락: PostgreSQL 연결, 권한, 공유 범위, report ID를 함께 확인

---

## 업데이트 이력

| 날짜 | 버전 | 내용 |
| --- | --- | --- |


---

이 매뉴얼은 현재 SQLAdvisor 웹 애플리케이션의 AWR 업로드, 분석, Advisor Chat 흐름을 기준으로 작성되었습니다.
