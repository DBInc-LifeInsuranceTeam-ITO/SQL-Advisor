# SQLAdvisor 1.0.0

최초 독립 설치형 Docker 배포 패키지입니다.

## 구성 요소

- SQLAdvisor Web
- SQLAdvisor API
- AWR Worker
- PostgreSQL 16 + pgvector
- Redis 7

## 설치 시 주의사항

- 설치 전 `.env.example`을 `.env`로 복사하고 비밀번호 및 AI 연동 설정을 변경하세요.
- 외부 공개 환경은 인증과 HTTPS를 반드시 구성하세요.
- 기존 버전에서 업그레이드하는 경우 데이터 백업 후 진행하세요.

