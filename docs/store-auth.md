# Store 인증 계약

store는 기존 카카오 로그인 세션 쿠키(`bt_session`)를 그대로 사용한다. 선택적으로 Bearer JWT도 검증한다. user 로그인 API를 JWT 발급 방식으로 변경하지 않는다. search도 같은 필터와 JWT 설정을 공유하며 상세 범위는 [search 인증](search-auth.md)을 따른다. notification·chatting 인증은 별도다.

## API별 적용

| ID | 메서드·경로 (`/api/v1` 기준) | 인증·권한 |
| --- | --- | --- |
| ST-01 | GET `/stores/categories` | 공개 |
| ST-02 | GET `/stores/{storeId}` | 공개 |
| ST-03 | GET `/stores/{storeId}/services` | 공개 |
| ST-04 | GET `/stores/{storeId}/slots` | 공개 |
| ST-05 | GET `/stores/{storeId}/slots/{slotId}` | 공개 |
| ST-22 | GET `/stores/{storeId}/timetable` | 공개, 시간만 반환·no-store |
| ST-06 | POST `/reservations` | 로그인 |
| ST-07 | GET `/reservations/me` | 로그인, 본인 목록 |
| ST-08 | GET `/reservations/{reservationId}` | 예약자 본인 또는 해당 매장 소유자 |
| ST-09 | POST `/reservations/{reservationId}/cancel` | 예약자 본인 또는 해당 매장 소유자 |
| ST-10 | PATCH `/owner/stores/{storeId}` | OWNER, 매장 소유자 |
| ST-11 | GET `/owner/stores/{storeId}/timetable` | OWNER, 매장 소유자 |
| ST-12 | GET `/owner/stores/{storeId}/reservations/{reservationId}` | OWNER, 매장 소유자 |
| ST-13 | PATCH `/owner/stores/{storeId}/services/{serviceId}` | OWNER, 매장 소유자 |
| ST-14 | GET `/owner/stores/{storeId}/vacancies` | OWNER, 매장 소유자 |
| ST-15 | POST `/owner/stores/{storeId}/vacancies/{vacancyId}/publish` | OWNER, 매장 소유자 |
| ST-16 | POST `/owner/stores/{storeId}/vacancies/{vacancyId}/withdraw` | OWNER, 매장 소유자 |
| ST-17 | POST `/owner/stores/{storeId}/reservations/{reservationId}/status` | OWNER, 매장 소유자 |
| ST-18 | POST `/owner/stores` | 로그인, 등록 성공 시 OWNER 자동 부여 |
| ST-19 | POST `/owner/stores/{storeId}/services` | OWNER, 매장 소유자 |
| ST-20 | POST `/owner/stores/{storeId}/slots` | OWNER, 매장 소유자 |

공개 조회는 매장 탐색과 예약 가능 여부 확인에 사용하므로 비로그인을 허용한다. 예약·관리 API는 개인정보 및 상태 변경을 포함하므로 인증한다. 역할·표시 이름은 JWT나 로그인 당시 역할을 신뢰하지 않고 매 요청 user 공개 서비스를 통해 현재 DB에서 읽는다. 예약 소유권·매장 소유권은 store 서비스에서 검증한다. DB에서 삭제된 사용자도 401이다. 명시적으로 제공한 JWT가 잘못되면 유효한 세션 쿠키가 함께 있어도 401이며 쿠키로 재시도하지 않는다.

## 사장님 등록

최초 가입 역할은 CUSTOMER다. 로그인한 CUSTOMER 또는 OWNER는 `POST /api/v1/owner/stores`로 매장을 등록할 수 있으며, 매장 소유자는 인증된 본인으로 고정한다. 매장·카탈로그 이벤트·멱등 성공 기록과 OWNER 부여는 하나의 트랜잭션으로 커밋하고 실패 시 모두 롤백한다. 같은 키의 동시 요청과 재시도는 기존 24시간 멱등 규칙을 따른다.

DB는 기존 단일 `role_code`를 유지한다. OWNER는 고객 기능도 사용할 수 있으며, 다른 매장의 관리 권한은 얻지 않는다. store·search는 다음 요청에서 현재 DB 역할을 조회하므로 재로그인이 필요 없다. 프론트는 등록 성공 후 `GET /api/v1/users/me`로 `roles`와 `ownedStoreIds`를 다시 조회한다. 별도 사업자 심사나 운영자 승인 절차는 적용하지 않는다.

## 쿠키 요청

카카오 로그인 후 브라우저에서 `credentials: 'include'`로 요청한다. GET에는 CSRF 헤더가 필요 없다. POST/PATCH/PUT/DELETE에는 `GET /api/v1/auth/csrf`에서 받은 토큰을 `X-CSRF-Token`으로 보낸다. store도 user와 동일한 Spring `HttpSessionCsrfTokenRepository`를 사용한다. 누락·불일치는 403 `CSRF_INVALID`다. 로그인하지 않은 요청은 401이다.

```javascript
const { success } = await fetch(`${API}/api/v1/auth/csrf`, {
  credentials: 'include',
}).then(response => response.json());
await fetch(`${API}/api/v1/reservations`, {
  method: 'POST', credentials: 'include',
  headers: {
    'Content-Type': 'application/json',
    'X-CSRF-Token': success.data.csrfToken,
    'Idempotency-Key': crypto.randomUUID(),
  },
  body: JSON.stringify(reservationRequest),
});
```

배포 쿠키의 Secure·SameSite 및 브라우저 쿠키 차단 조건은 [카카오 로그인](kakao-login.md)을 따른다. 로컬 CORS는 설정된 프론트 origin과 credentials 및 Authorization/Idempotency-Key/CSRF 헤더를 허용한다. 배포에서는 기존 Nginx 허용 목록에도 해당 헤더가 필요하다.

## Bearer JWT

JWT를 발급하는 신뢰 가능한 인증 시스템이 있을 때 다음 애플리케이션 설정을 추가한다. 기본값은 비활성화이며 쿠키 인증은 JWT 설정 없이 동작한다.

```properties
bintime.auth.store-jwt.enabled=true
bintime.auth.store-jwt.issuer=https://your-issuer.example
bintime.auth.store-jwt.jwk-set-uri=https://your-issuer.example/.well-known/jwks.json
bintime.auth.store-jwt.audience=bintime-store
```

Spring Security의 `BearerTokenAuthenticationFilter`와 `NimbusJwtDecoder`가 RS256 서명, issuer, audience, exp, nbf를 검증한다. exp와 비어 있지 않은 sub는 필수다. sub는 카카오 ID가 아닌 `user_accounts.user_id`다. 시간 검증에는 Spring 기본 시계 오차 허용이 적용된다. 토큰은 `Authorization: Bearer <JWT>`에만 넣으며 URL·요청 본문·쿠키에서는 받지 않는다. 카카오 access token을 빈틈의 JWT로 사용할 수 없다.

Bearer 인증은 요청 단위이며 세션에 저장하지 않는다. 브라우저가 자동으로 첨부하는 쿠키를 인증 수단으로 사용하지 않으므로 Bearer 요청에는 CSRF 헤더를 요구하지 않는다. JWT가 틀리면 쿠키로 대체하지 않아 CSRF 우회를 허용하지 않는다. 이번 작업은 JWT 발급·갱신·폐기 API를 추가하지 않는다. JWT는 발급자의 만료·키 관리 정책을 따르며 세션 로그아웃은 이미 발급된 JWT를 폐기하지 않는다.

## 개발·문서·검증

기존 `local`/`test` + `bintime.auth.local-enabled=true`에서만 `X-Local-User-Id` 대체 식별을 지원한다. prod가 함께 활성화되면 무효다. 대체 식별은 QA용이며 기존 계정이 있어야 한다. `api-docs`는 실행용이 아닌 문서용 프로필이다.

Swagger의 보호 store operation은 sessionCookie 또는 bearerJwt 중 하나를 선택한다. 공개 6개 operation에는 인증 요구를 선언하지 않는다.

`StoreAuthenticationTests`는 세션·CSRF, 공개/보호 구분, 현재 DB 역할, 삭제 계정, 실제 RSA 서명 JWT·만료·issuer/audience 불일치·위조와 쿠키 대체 차단을 검증한다. 기존 `StoreApiTests`는 소유권·예약·동시성·멱등성 및 local QA 경로를 검증한다. 실제 카카오 계정과 배포 발급자의 JWT를 사용한 브라우저 QA는 별도다.
