# SQLAdvisor Frontend

Vue 3, TypeScript, Vite 기반의 SQLAdvisor 웹 화면입니다. AWR 업로드, 리포트 목록, 상세 분석, Advisor Chat, SQL 튜닝, AI 설정 화면을 제공합니다.

## 전체 스택 실행

배포 관련 파일은 루트의 `deploy/` 폴더에 있습니다.

Dev:

```bash
sh deploy/init-env.sh --mode dev
docker compose -f deploy/docker-compose.dev.yml up -d --build
```

Prod:

```bash
sh deploy/init-env.sh --mode prod
docker compose -f deploy/docker-compose.prod.yml up -d --build
```

prod에서는 Nginx가 정적 프론트엔드를 서빙하고 `/api` 요청을 내부 API 컨테이너로 프록시합니다.

## 화면 구성

```text
/dashboard          AWR 대시보드
/login              로그인
/upload             AWR 파일 업로드
/reports            업로드된 AWR 리포트 목록
/reports/:id        리포트 상세, Top SQL, Wait Event, 분석 결과
/chat               리포트 선택형 Advisor Chat
/reports/:id/chat   특정 리포트 기반 Advisor Chat
/sql-tuning         Direct DB 또는 직접 입력 기반 SQL 튜닝
/settings/ai        AI provider 및 API key 설정
```

## 기술 스택

- Vue 3
- TypeScript
- Vite
- Vue Router
- Axios
- Nginx 정적 서빙 및 `/api` 프록시

## 프론트 로컬 개발

백엔드가 `localhost:8080`에서 실행 중이면 Vite 개발 서버의 `/api` 프록시가 동작합니다.

```bash
npm install
npm run dev
```

프론트 개발 서버는 기본적으로 `http://localhost:5173`에서 열립니다.

## 주요 명령어

```bash
npm install
npm run dev
npm run build
npm run type-check
npm run lint
npm run preview
```

## API 연동

`src/api/awr.ts`, `src/api/auth.ts`, `src/api/sqlTuning.ts`가 백엔드 `/api` 엔드포인트를 감싼 클라이언트입니다. 공통 Axios 인스턴스는 `src/services/api.ts`에 있습니다.

| Method | Path | 용도 |
| --- | --- | --- |
| `GET` | `/config/ai` | AI provider 및 API key 설정 상태 조회 |
| `POST` | `/config/ai` | AI provider 및 API key 설정 저장 |
| `GET` | `/config/ai/models` | 지원 모델 목록 조회 |
| `GET` | `/auth/config` | 로그인 활성화 여부와 인증 모드 조회 |
| `GET` | `/auth/me` | 현재 사용자 조회 |
| `POST` | `/reports` | AWR 파일 업로드 |
| `GET` | `/reports` | 리포트 목록 조회 |
| `GET` | `/reports/{reportId}` | 리포트 상세 조회 |
| `GET` | `/reports/{reportId}/status` | 파싱/인덱싱 상태 조회 |
| `POST` | `/reports/{reportId}/analyze` | 병목 분석 실행 |
| `POST` | `/reports/{reportId}/chat` | 리포트 근거 기반 질의응답 |
| `GET` | `/reports/{reportId}/chat/history` | 리포트 Chat 히스토리 조회 |
| `GET` | `/reports/{reportId}/sql` | 구조화된 SQL metric 조회 |
| `POST` | `/reports/{reportId}/sql/{sqlId}/tune` | AWR SQL_ID 기반 튜닝 실행 |
| `GET` | `/db-connections` | Target DB 연결 목록 조회 |
| `POST` | `/db-connections` | Target DB 연결 저장 |
| `POST` | `/db-connections/test` | Target DB 연결 테스트 |
| `POST` | `/sql-tuning` | 직접 입력 SQL 튜닝 실행 |
| `GET` | `/sql-tuning/history` | SQL 튜닝 히스토리 조회 |
| `GET` | `/sql-tuning/direct/top-sql` | Direct DB Top SQL 후보 조회 |
| `POST` | `/sql-tuning/direct/context` | Direct DB SQL_ID 근거 수집 |
| `POST` | `/sql-tuning/direct` | Direct DB SQL_ID 튜닝 실행 |
