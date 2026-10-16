# Search 인증 연결

search의 사용자 경계는 `SearchUserAccessAdapter`가 구현한다. 카카오 로그인으로 생성한
`bt_session` 세션 또는 설정된 발급자의 Bearer JWT에서 사용자 ID를 얻고, user 원장의
현재 역할이 `CUSTOMER`인지 다시 확인한다. 세션·토큰의 역할 주장으로 권한을 결정하지 않는다.

| API | 인증 정책 |
| --- | --- |
| S-01 `GET /api/v1/search/stores` | 비로그인 허용 |
| S-02 `POST /api/v1/search/intents/analyze` | CUSTOMER 인증, 쿠키 요청 CSRF 필수 |
| S-03 `POST /api/v1/search/waitlists` | CUSTOMER 인증, 쿠키 요청 CSRF 필수 |
| S-04 `GET /api/v1/search/waitlists/me` | CUSTOMER 인증, 본인 데이터 |
| S-05 `GET /api/v1/search/waitlists/{id}` | CUSTOMER 인증, 본인 데이터 |
| S-06 `PATCH /api/v1/search/waitlists/{id}` | CUSTOMER 인증, 본인 데이터, 쿠키 요청 CSRF 필수 |
| S-07 `POST /api/v1/search/waitlists/{id}/cancel` | CUSTOMER 인증, 본인 데이터, 쿠키 요청 CSRF 필수 |
| S-08 `/api/v1/internal/search/**` | 기존 `X-Internal-Token` 검증, 사용자 로그인으로 대체 불가 |

쿠키 변경 요청은 로그인 세션으로 `GET /api/v1/auth/csrf`에서 받은 토큰을
`X-CSRF-Token`에 넣는다. store와 같은 보안 필터가 search 경로도 처리한다.
Bearer JWT는 기존 `bintime.auth.store-jwt.*` 설정을 공유하며, JWT 발급·갱신 API는 추가하지 않는다.
검증된 Bearer 인증 요청에는 쿠키 CSRF 토큰이 필요하지 않다. OWNER는 고객 대기 API를 사용할 수 없다.
다른 고객의 대기는 기존 소유권 정책에 따라 404로 응답한다.

`SearchHttpGuard`는 S-01을 제외한 사용자 search API에서 `customer()`를 호출한다.
명시적 선호도는 기존 `UserPreferenceService`로 읽는다. Swagger 전용 `api-docs` 프로필은
DB 없는 계약 조회를 위한 미연결 구현을 유지한다.

알림 전역 설정과 실제 전달은 아직 연결되지 않았다. `notificationsEnabled()`와
`SearchNotificationGateway`는 계속 503 미연결 오류를 반환한다. 대기 등록의
`notificationConsent=true`를 전역 알림 설정으로 임의 변환하지 않는다.
따라서 이 작업은 로그인 후 대기 등록·조회·수정·취소를 열며 실제 알림 발송 완료를 의미하지 않는다.

검증: SearchAuthenticationTests의 세션 기반 대기 CRUD·역할/소유권·CSRF·삭제 계정·공개/내부 API 경계와 StoreAuthenticationTests의 실제 RSA 서명 Bearer JWT search 요청이 통과했다. 전체 포맷·테스트·빌드도 통과했다. 실제 카카오 브라우저 로그인·배포 JWT 발급자와의 QA는 별도다.
