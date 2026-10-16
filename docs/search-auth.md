# Search 인증 연결

search의 사용자 경계는 `SearchUserAccessAdapter`가 구현한다. 카카오 로그인으로 생성한
`bt_session` 세션 또는 설정된 발급자의 Bearer JWT에서 사용자 ID를 얻고, user 원장의
현재 역할이 `CUSTOMER` 또는 `OWNER`인지 다시 확인한다. 세션·토큰의 역할 주장으로 권한을 결정하지 않는다.

| API | 인증 정책 |
| --- | --- |
| S-01 `GET /api/v1/search/stores` | 비로그인 허용 |
| S-02 `POST /api/v1/search/intents/analyze` | CUSTOMER 또는 OWNER 인증, 쿠키 요청 CSRF 필수 |
| S-03 `POST /api/v1/search/waitlists` | CUSTOMER 또는 OWNER 인증, 쿠키 요청 CSRF 필수 |
| S-04 `GET /api/v1/search/waitlists/me` | CUSTOMER 또는 OWNER 인증, 본인 데이터 |
| S-05 `GET /api/v1/search/waitlists/{id}` | CUSTOMER 또는 OWNER 인증, 본인 데이터 |
| S-06 `PATCH /api/v1/search/waitlists/{id}` | CUSTOMER 또는 OWNER 인증, 본인 데이터, 쿠키 요청 CSRF 필수 |
| S-07 `POST /api/v1/search/waitlists/{id}/cancel` | CUSTOMER 또는 OWNER 인증, 본인 데이터, 쿠키 요청 CSRF 필수 |
| S-08 `/api/v1/internal/search/**` | 기존 `X-Internal-Token` 검증, 사용자 로그인으로 대체 불가 |

쿠키 변경 요청은 로그인 세션으로 `GET /api/v1/auth/csrf`에서 받은 토큰을
`X-CSRF-Token`에 넣는다. store와 같은 보안 필터가 search 경로도 처리한다.
Bearer JWT는 기존 `bintime.auth.store-jwt.*` 설정을 공유하며, JWT 발급·갱신 API는 추가하지 않는다.
검증된 Bearer 인증 요청에는 쿠키 CSRF 토큰이 필요하지 않다. OWNER도 본인의 고객 대기 API를 사용할 수 있다.
다른 고객의 대기는 기존 소유권 정책에 따라 404로 응답한다.

`SearchHttpGuard`는 S-01을 제외한 사용자 search API에서 `customer()`를 호출한다.
명시적 선호도는 기존 `UserPreferenceService`로 읽는다. Swagger 전용 `api-docs` 프로필은
DB 없는 계약 조회를 위한 미연결 구현을 유지한다.

`notificationsEnabled()`는 `NotificationSubscriptions.hasActive`로 유효한 세션에 연결된
Firebase Installation ID 유무를 확인한다. `SearchNotificationGateway`는
`NotificationService`에 연결된다. 대기 등록의 `notificationConsent=true`를 구독으로
간주하지 않으며, 실제 FCM 발송에는 별도 설정과 활성 세션 구독이 필요하다.

검증: SearchAuthenticationTests의 세션 기반 대기 CRUD·역할/소유권·CSRF·삭제 계정·공개/내부 API 경계와 StoreAuthenticationTests의 실제 RSA 서명 Bearer JWT search 요청이 통과했다. 전체 포맷·테스트·빌드도 통과했다. 실제 카카오 브라우저 로그인·배포 JWT 발급자와의 QA는 별도다.
