# SQLAdvisor 사용자 매뉴얼

## 1. 시스템 개요

SQLAdvisor는 Oracle DB의 실시간 SQL 상태와 AWR 기반 성능 정보를 함께 확인하고, AI 또는 규칙 기반 분석을 통해 SQL 튜닝 권고를 제공하는 웹 도구입니다.

### 주요 기능

- 등록된 Oracle DB의 현재 실행 SQL 모니터링
- 30초 이상 장기 실행 SQL 확인
- 주의 SQL 및 Blocking 세션 확인
- Active Sessions, CPU, I/O 활동 추이 확인
- Direct DB Top SQL 조회 및 SQL_ID 기반 튜닝
- 직접 입력 SQL 기반 튜닝
- AWR HTML/TXT/PDF 업로드 및 구조화
- Top SQL, Wait Event, DB Time 기반 병목 분석
- Advisor Chat 및 분석 히스토리

## 2. 사용 전 준비

### 접속 주소

| 환경 | Web | API |
| --- | --- | --- |
| Dev | `http://localhost:5173` | `http://localhost:18080/api` |
| Prod | 관리자가 제공한 URL | `<웹 주소>/api` |

### Target DB 준비

실시간 대시보드와 Direct DB 튜닝을 사용하려면 SQLAdvisor에 Oracle 연결 정보가 등록되어 있어야 합니다.

권장 사항:

- 운영 DB는 조회 전용 계정 사용
- `V$SESSION`, `V$SQL`, 실행계획 및 인덱스 조회에 필요한 권한만 부여
- 비밀번호와 운영 URL은 소스 또는 문서에 기록하지 않음

## 3. 화면별 사용법

### 3.1 실시간 대시보드

경로: `/dashboard`

대상 DB를 선택하면 Oracle의 현재 SQL 상태를 주기적으로 조회합니다. 현재 화면 갱신 주기는 2초입니다.

#### 요약 지표

| 항목 | 의미 |
| --- | --- |
| 현재 실행 SQL | 현재 Active 상태로 확인된 SQL 수 |
| 장기 실행 SQL | 30초 이상 수행 중인 SQL 수 |
| 주의 SQL | 설정된 임계값을 초과한 SQL 수 |
| Blocking 세션 | 다른 세션을 대기시키는 Blocking 상태 수 |

#### DB 활동 추이

탭을 선택해 최근 활동을 확인합니다.

- `Active Sessions`: 활성 세션 수
- `CPU`: SQL 수행에 사용된 CPU 관련 지표
- `I/O`: 읽기 및 I/O 관련 지표

그래프 상단에는 현재값, 최근 평균, 최고값이 표시됩니다.

#### 부하 상위 SQL

총 수행시간 기준으로 SQL_ID, 모듈, 수행시간, Buffer Gets, Disk Reads, 실행 횟수를 표시합니다. 실시간 요약 지표보다 조회 비용이 크므로 별도 주기로 갱신될 수 있습니다.

#### 테스트 데이터

대상 DB에서 `TEST`를 선택하면 프론트엔드에 포함된 임시 데이터로 화면 동작을 확인할 수 있습니다. 실제 Oracle DB나 PostgreSQL에 테스트 데이터가 저장되는 방식은 아닙니다.

### 3.2 SQL 튜닝

경로: `/sql-tuning`

#### Direct DB 방식

1. 연결된 Oracle DB를 선택합니다.
2. Top SQL 후보를 조회합니다.
3. SQL_ID를 선택합니다.
4. SQL text, 실행계획, 인덱스, bind 근거를 확인합니다.
5. 튜닝을 실행합니다.
6. 증상, 원인 가설, 권장 조치, 검증 SQL을 확인합니다.

Direct DB Top SQL은 Oracle에서 조회 가능한 SQL 중 설정한 정렬 기준과 필터에 맞는 후보입니다.

#### 직접 입력 방식

1. SQL text를 입력합니다.
2. 가능하면 실행계획, DDL, 기존 인덱스, bind 정보를 함께 입력합니다.
3. 분석을 실행합니다.
4. 권고안과 검증 SQL을 확인합니다.

튜닝 결과는 운영 반영 전 반드시 테스트 환경에서 검증해야 합니다.

### 3.3 AWR 업로드

경로: `/upload`

1. AWR 파일을 선택합니다.
2. 공유 범위를 설정합니다.
3. 분석을 시작합니다.
4. 처리 상태를 확인합니다.
5. 완료 후 리포트 상세 또는 Advisor Chat으로 이동합니다.

지원 확장자:

- `.html`, `.htm`
- `.txt`, `.log`
- `.pdf`

HTML과 TXT가 가장 안정적입니다. PDF는 OCR/텍스트 추출 Worker 상태에 따라 처리 시간이 늘어나거나 별도 추출이 필요할 수 있습니다.

### 3.4 AWR 리포트 목록

경로: `/reports`

업로드된 리포트의 파일명, DB, Instance, Snapshot 구간, 처리 상태, SQL 및 Wait Event 건수를 확인합니다.

### 3.5 AWR 리포트 상세

경로: `/reports/:id`

확인 가능한 항목:

- DB 및 Instance 정보
- Snapshot 시작/종료
- DB Time
- Top SQL
- Top Wait Events
- 파싱된 원문 섹션
- 최신 분석 결과

병목 분석 결과는 AWR에 포함된 정보만을 근거로 하므로 실행계획, 실제 row 수, bind, 통계정보가 없으면 가설 수준으로 해석해야 합니다.

### 3.6 Advisor Chat

경로: `/chat` 또는 `/reports/:id/chat`

선택한 AWR 리포트의 SQL 지표와 Wait Event를 근거로 질문에 답합니다.

예시 질문:

- 가장 먼저 확인할 SQL은 무엇인가?
- CPU 병목인지 I/O 병목인지 판단해줘.
- 특정 SQL_ID의 문제 원인을 설명해줘.
- Top Wait Event 기준으로 우선 조치를 알려줘.

### 3.7 AI 설정

경로: `/settings/ai`

관리자는 LLM provider, 모델, API key 설정 상태를 확인하고 저장할 수 있습니다. 외부 LLM이 비활성화되거나 호출에 실패하면 로컬 규칙 기반 분석으로 동작할 수 있습니다.

## 4. 권한별 접근

| 역할 | 주요 접근 범위 |
| --- | --- |
| `ADMIN` | 전체 화면, AI 설정, 사용자 관리 |
| `USER` | 대시보드, AWR, Chat, SQL 튜닝 |
| `MONITOR` | 대시보드, 리포트 조회, SQL 튜닝 조회 중심 기능 |

인증이 비활성화된 환경에서는 별도 로그인 없이 사용할 수 있습니다.

## 5. 실시간 모니터링 테스트

### 장기 실행 SQL

권한이 있다면 DBeaver에서 다음과 같이 실행할 수 있습니다.

```sql
BEGIN
  DBMS_LOCK.SLEEP(60);
END;
/
```

30초 이후 장기 실행 SQL 지표가 증가하는지 확인합니다.

### Blocking 세션

세션 1에서 UPDATE 후 COMMIT하지 않고 유지한 뒤, 세션 2에서 같은 행을 UPDATE하면 Blocking 상태를 확인할 수 있습니다. 테스트 종료 후 반드시 `ROLLBACK` 또는 `COMMIT`을 수행합니다.

운영 DB에서는 부하가 큰 Cartesian Join이나 무제한 반복 SQL을 테스트 목적으로 실행하지 않습니다.

## 6. 자주 발생하는 문제

### 대시보드 값이 표시되지 않음

- Target DB 연결 테스트 확인
- Oracle 조회 계정 권한 확인
- API 로그 확인
- 브라우저 개발자 도구의 `/api/monitoring/dashboard` 응답 확인

### 새로고침 직후 TEST 값이 표시되지 않음

현재 코드는 컴포넌트 초기화 후 즉시 첫 수집을 실행하도록 구성되어 있습니다. 이전 빌드가 남아 있다면 컨테이너 재빌드 후 강력 새로고침합니다.

### 수집 실패 표시

오류 메시지와 API 로그를 확인합니다.

```bash
docker compose -f deploy/docker-compose.prod.yml logs -f api
```

### AWR PDF가 처리되지 않음

OCR Worker와 Redis 상태를 확인하거나 Oracle에서 HTML/TXT 형식으로 다시 생성합니다.

## 7. 운영 주의사항

- 실시간 조회 주기가 짧을수록 Oracle과 API 부하가 증가합니다.
- 운영 DB에는 조회 전용 계정을 사용합니다.
- Blocking 테스트는 테스트 DB에서 수행합니다.
- 인덱스 추가, SQL rewrite, Hint 적용은 테스트 후 반영합니다.
- AI 분석 결과만으로 운영 변경을 확정하지 않습니다.
