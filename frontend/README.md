# SQLAdvisor Frontend

Vue 3, TypeScript, Vite 기반의 SQLAdvisor 웹 화면입니다.

현재 주요 화면은 Oracle 실시간 SQL 대시보드, Direct DB SQL 튜닝, AWR 업로드/분석, Advisor Chat, AI 설정입니다.

## 화면 구성

```text
/dashboard          Oracle 실시간 SQL 대시보드
/login              로그인
/upload             AWR 파일 업로드
/reports            AWR 리포트 목록
/reports/:id        AWR 상세, Top SQL, Wait Event, 분석 결과
/chat               리포트 선택형 Advisor Chat
/reports/:id/chat   특정 리포트 기반 Advisor Chat
/sql-tuning         Direct DB 또는 직접 입력 기반 SQL 튜닝
/settings/ai        AI provider 및 API key 설정
/settings/users     사용자 관리
```

## 실시간 대시보드

`/dashboard`는 등록된 Oracle DB를 선택해 다음 항목을 표시합니다.

- 현재 실행 SQL
- 30초 이상 장기 실행 SQL
- 주의 SQL
- Blocking 세션
- Active Sessions, CPU, I/O 활동 그래프
- 총 수행시간 기준 부하 상위 SQL

현재 화면 갱신 주기는 2초입니다. Top SQL은 Oracle 조회 부하를 줄이기 위해 별도 주기로 호출됩니다.

`TEST` 선택지는 프론트엔드 임시 데이터이며 실제 DB에 저장되지 않습니다.

주요 파일:

```text
src/views/awr/AwrDashboard.vue
src/views/awr/AwrDashboard.css
src/api/monitoring.ts
src/api/sqlTuning.ts
```

## 기술 스택

- Vue 3
- TypeScript
- Vite
- Vue Router
- Pinia
- Axios
- SVG 기반 실시간 차트
- Nginx 정적 서빙 및 `/api` 프록시

## 빌드 및 검증

```bash
npm install
npm run build
npm run type-check
npm run lint
```

## 운영 배포

저장소 루트에서 운영 Compose로 전체 스택을 실행합니다.

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

운영 환경에서는 Nginx가 빌드된 프론트엔드 정적 파일을 서빙하고 `/api` 요청을 API 컨테이너로 프록시합니다.

## API 연동

공통 Axios 인스턴스는 `src/services/api.ts`에 있습니다.

| 클라이언트 | 역할 |
| --- | --- |
| `src/api/monitoring.ts` | 실시간 대시보드 조회 |
| `src/api/sqlTuning.ts` | DB 연결, Top SQL, SQL 튜닝 |
| `src/api/awr.ts` | AWR 업로드, 목록, 상세, 분석, Chat |
| `src/api/auth.ts` | 로그인 및 사용자 상태 |

주요 API:

| Method | Path | 용도 |
| --- | --- | --- |
| `GET` | `/monitoring/dashboard?connectionId={id}` | 실시간 DB 상태 조회 |
| `GET` | `/db-connections` | Target DB 목록 조회 |
| `POST` | `/db-connections` | Target DB 저장 |
| `POST` | `/db-connections/test` | Target DB 연결 테스트 |
| `GET` | `/sql-tuning/direct/top-sql` | Direct DB Top SQL 조회 |
| `POST` | `/sql-tuning/direct/context` | SQL_ID 근거 수집 |
| `POST` | `/sql-tuning/direct` | Direct DB SQL 튜닝 |
| `POST` | `/sql-tuning` | 직접 입력 SQL 튜닝 |
| `POST` | `/reports` | AWR 업로드 |
| `GET` | `/reports` | AWR 목록 조회 |
| `GET` | `/reports/{reportId}` | AWR 상세 조회 |
| `POST` | `/reports/{reportId}/analyze` | 병목 분석 |
| `POST` | `/reports/{reportId}/chat` | Advisor Chat |
| `GET` | `/config/ai` | AI 설정 조회 |
| `POST` | `/config/ai` | AI 설정 저장 |

## 운영 주의사항

- 대시보드 폴링 주기를 줄이면 API와 Oracle 호출량이 증가합니다.
- 실시간 화면 스타일은 `AwrDashboard.css`와 전역 보정 CSS의 우선순위를 함께 확인해야 합니다.
- API 응답의 서버 시각은 화면에서 `Asia/Seoul` 기준으로 표시합니다.
- 운영 배포 후 이전 정적 파일이 보이면 컨테이너 재빌드 후 브라우저 강력 새로고침을 수행합니다.
