# SQLAdvisor 로그인 설계

SQLAdvisor의 로그인은 배포 환경에 따라 외부용 Google 로그인과 내부용 AD 계정 식별자 로그인을 선택할 수 있습니다. 사용자와 인증 수단은 분리해서 저장하며, 질문과 채팅 히스토리는 사용자별로 분리합니다.

## 실행 설정

로그인은 기본 비활성화입니다. 활성화하려면 환경 파일에서 `APP_AUTH_ENABLED=true`를 설정하고, `APP_AUTH_MODE`로 방식을 선택합니다.

외부용 Google 로그인:

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=external
GOOGLE_CLIENT_ID=<Google OAuth Client ID>
APP_ALLOWED_GOOGLE_DOMAINS=
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

내부용 AD 계정 식별자 로그인:

```env
APP_AUTH_ENABLED=true
APP_AUTH_MODE=internal
APP_INTERNAL_AUTH_EMAIL_DOMAIN=internal.local
APP_ADMIN_EMAILS=admin@example.com
APP_LOCAL_LOGIN_ENABLED=false
APP_SESSION_TIMEOUT_MINUTES=120
```

`APP_AUTH_ENABLED=false`이면 기존처럼 로그인 없이 사용할 수 있습니다. `APP_AUTH_MODE=external`이면 Google 로그인만 사용하고, `APP_AUTH_MODE=internal`이면 Google 로그인 대신 AD 계정 식별자 흐름을 사용합니다.

## 인증 흐름

### 외부용 Google

1. 프론트엔드가 Google 로그인으로 리다이렉트합니다.
2. 사용자가 Google 계정으로 로그인하면 브라우저가 ID Token을 받습니다.
3. 프론트엔드는 `POST /api/auth/google`로 ID Token을 서버에 전달합니다.
4. 서버는 Google Client ID 기준으로 토큰을 검증하고, 이메일 인증 여부와 허용 도메인을 확인합니다.
5. `app_user`와 `auth_identity(provider='google')`에 사용자를 생성하거나 갱신합니다.
6. 서버 세션에 사용자 정보를 저장하고 HttpOnly 세션 쿠키로 유지합니다.

### 내부용 AD 계정 식별자

1. 내부 포털 또는 프록시가 `loginEno` 쿼리 파라미터나 `X-Login-Eno` 헤더로 AD 계정 식별자를 전달합니다.
2. 프론트엔드는 `POST /api/auth/internal`을 호출합니다.
3. 서버는 요청 body, `loginEno` 파라미터, `X-Login-Eno` 헤더, 기존 세션 순서로 식별자를 확인합니다.
4. 식별자가 이메일 형식이면 그대로 사용하고, 아니면 `APP_INTERNAL_AUTH_EMAIL_DOMAIN`을 붙여 사용자 이메일을 만듭니다.
5. `app_user`와 `auth_identity(provider='internal-ad')`에 사용자를 생성하거나 갱신합니다.
6. `loginEno`가 없으면 로그인하지 않은 상태로 남으며, 화면에는 사용자 정보 없음 상태를 표시합니다.

## DB 테이블 구조

| 테이블 | 용도 |
| --- | --- |
| `app_user` | 서비스 내부 사용자. Google/내부 AD 계정 식별자와 무관한 공통 사용자 기준입니다. |
| `auth_identity` | 로그인 수단. Google, 내부 AD 계정 식별자, 향후 로컬 로그인을 같은 사용자에 연결합니다. |
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
| `picture_url` | Google 프로필 이미지. 내부용 사용자는 비어 있을 수 있습니다. |
| `role` | `USER`, `ADMIN` |
| `status` | `ACTIVE`, `DISABLED` |
| `last_login_at` | 마지막 로그인 시각 |

### `auth_identity`

| 컬럼 | 설명 |
| --- | --- |
| `user_id` | `app_user.id` 참조 |
| `provider` | `google`, `internal-ad`, 향후 `local` |
| `provider_user_id` | Google subject, 내부 AD 계정 식별자, 향후 로컬 로그인 ID |
| `email` | 인증 수단이 제공한 이메일 |
| `password_hash` | 로컬 로그인 확장용 비밀번호 해시 |

### 사용자별 히스토리

`analysis_result.user_id`가 현재 로그인 사용자를 가리킵니다. 로그인 비활성 상태에서 생성된 기존 데이터는 `user_id`가 `NULL`로 남아 기존 사용 흐름과 호환됩니다.

`awr_report.visibility`는 AWR 리포트 공개 범위입니다. `SHARED`는 모든 로그인 사용자가 볼 수 있고, `PRIVATE`는 `awr_report.uploaded_by`와 같은 사용자만 볼 수 있습니다. 단, `ADMIN` 역할 사용자는 운영 확인을 위해 비공유 리포트도 조회할 수 있습니다.

일반 사용자는 자신의 `analysis_result`만 채팅 히스토리로 조회합니다. 그래서 같은 AWR 리포트를 보더라도 사용자별 질문과 답변 내역은 섞이지 않습니다.

`ADMIN` 역할 사용자는 운영 확인을 위해 같은 AWR 리포트의 전체 Advisor Chat 히스토리를 조회할 수 있습니다.

## 향후 로컬 로그인 확장

로컬 로그인을 추가할 때도 새 사용자 테이블을 만들지 않고 `auth_identity`에 `provider='local'` 행을 추가합니다. 비밀번호는 `password_hash`에 BCrypt 해시로 저장하고, 기존 `app_user`는 그대로 사용합니다.
