# SQLAdvisor 로그인 설계

SQLAdvisor의 로그인은 Google 로그인을 먼저 지원하고, 나중에 로컬 로그인까지 확장할 수 있도록 사용자와 인증 수단을 분리합니다. 질문과 채팅 히스토리는 사용자별로 분리해서 저장합니다.

## 실행 설정

로그인은 기본 비활성화입니다. 활성화하려면 환경 파일에 아래 값을 설정합니다.

```env
APP_AUTH_ENABLED=true
GOOGLE_CLIENT_ID=<Google OAuth Client ID>
APP_ALLOWED_GOOGLE_DOMAINS=
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

`APP_AUTH_ENABLED=false`이면 기존처럼 로그인 없이 사용할 수 있습니다. `GOOGLE_CLIENT_ID`가 비어 있으면 로그인 화면에서 Google 버튼을 렌더링하지 않습니다.

## 인증 흐름

1. 프론트엔드가 Google Identity Services 버튼을 표시합니다.
2. 사용자가 Google 계정으로 로그인하면 브라우저가 ID Token을 받습니다.
3. 프론트엔드는 `POST /api/auth/google`로 ID Token을 서버에 전달합니다.
4. 서버는 Google Client ID 기준으로 토큰을 검증하고, 이메일 인증 여부와 허용 도메인을 확인합니다.
5. `app_user`와 `auth_identity`에 사용자를 생성하거나 갱신합니다.
6. 서버 세션에 사용자 정보를 저장하고 HttpOnly 세션 쿠키로 유지합니다.

## DB 테이블 구조

| 테이블 | 용도 |
| --- | --- |
| `app_user` | 서비스 내부 사용자. Google/로컬 로그인과 무관한 공통 사용자 기준입니다. |
| `auth_identity` | 로그인 수단. Google, 향후 로컬 로그인을 같은 사용자에 연결합니다. |
| `auth_login_audit` | 로그인 성공/실패 이력입니다. |
| `awr_report` | AWR 리포트 메타데이터. 업로드 사용자와 공개 범위를 보관합니다. |
| `analysis_result` | 분석/채팅 결과. `user_id`로 사용자별 질문 히스토리를 분리합니다. |
| `feedback` | 분석 피드백. 피드백 작성 사용자를 보관합니다. |
| `awr_ai_setting` | AI 설정. 마지막 수정 사용자를 보관합니다. |

## 주요 컬럼

### `app_user`

| 컬럼 | 설명 |
| --- | --- |
| `id` | 내부 사용자 ID |
| `email` | 로그인 기준 이메일 |
| `display_name` | 화면 표시 이름 |
| `picture_url` | Google 프로필 이미지 |
| `role` | `USER`, `ADMIN` |
| `status` | `ACTIVE`, `DISABLED` |
| `last_login_at` | 마지막 로그인 시각 |

### `auth_identity`

| 컬럼 | 설명 |
| --- | --- |
| `user_id` | `app_user.id` 참조 |
| `provider` | `google`, 향후 `local` |
| `provider_user_id` | Google subject 또는 로컬 로그인 ID |
| `email` | 인증 수단이 제공한 이메일 |
| `password_hash` | 로컬 로그인 확장용 비밀번호 해시 |

### 사용자별 히스토리

`analysis_result.user_id`가 현재 로그인 사용자를 가리킵니다. 로그인 비활성 상태에서 생성된 기존 데이터는 `user_id`가 `NULL`로 남아 기존 사용 흐름과 호환됩니다.

`awr_report.visibility`는 AWR 리포트 공개 범위입니다. `SHARED`는 모든 로그인 사용자가 볼 수 있고, `PRIVATE`는 `awr_report.uploaded_by`와 같은 사용자만 볼 수 있습니다. 단, `ADMIN` 역할 사용자는 운영 확인을 위해 비공유 리포트도 조회할 수 있습니다.

일반 사용자는 자신의 `analysis_result`만 채팅 히스토리로 조회합니다. 그래서 같은 AWR 리포트를 보더라도 사용자별 질문과 답변 내역은 섞이지 않습니다.

`ADMIN` 역할 사용자는 운영 확인을 위해 같은 AWR 리포트의 전체 Advisor Chat 히스토리를 조회할 수 있습니다.

## 향후 로컬 로그인 확장

로컬 로그인을 추가할 때는 새 사용자 테이블을 만들지 않고 `auth_identity`에 `provider='local'` 행을 추가합니다. 비밀번호는 `password_hash`에 BCrypt 해시로 저장하고, 기존 `app_user`는 그대로 사용합니다.
