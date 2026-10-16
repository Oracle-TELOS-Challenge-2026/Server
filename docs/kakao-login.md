# 카카오 로그인 실행

구현한 API는 U-01(로그인 시작), U-02(콜백), U-03(로그아웃), U-09(CSRF 조회)다. 경로와 응답 봉투는 기존 Swagger 계약을 유지한다. 사용자 프로필·위치·선호도(U-04~U-08)도 구현되어 있다. DB 마이그레이션과 사용법은 [사용자 API](user-profile.md)를 참고한다. 다른 도메인의 인증·권한 적용은 각 기능 구현 시 진행한다.

## 로컬 설정

저장소 루트의 기존 `.env`에 아래 키를 추가한다. REST API 키와 Client Secret의 실제 값은 Git에 넣지 않는다.

```properties
KAKAO_CLIENT_ID=카카오_REST_API_키
KAKAO_CLIENT_SECRET=해당_키의_Client_Secret
KAKAO_REDIRECT_URI=http://localhost:8080/api/v1/auth/oauth/kakao/callback
FRONTEND_URL=http://localhost:5173
AUTH_ALLOWED_RETURN_PATHS=/
```

Client Secret을 사용하지 않는 기존 키라면 `KAKAO_CLIENT_SECRET`은 비워둘 수 있다. 활성화된 키에는 반드시 실제 Secret을 넣는다. `KAKAO_CLIENT_ID`가 비어 있어도 서버와 Swagger는 실행되고, 로그인 시작은 503을 반환한다.

카카오 Developers에서 카카오 로그인을 활성화하고 **해당 REST API 키의 로그인 리다이렉트 URI**에 `KAKAO_REDIRECT_URI`를 정확히 등록한다. 이전 `/login/oauth2/code/kakao`와 이번 명세의 `/api/v1/auth/oauth/kakao/callback`은 다른 주소다. 이메일·전화번호는 요청하지 않는다. 닉네임 동의가 없으면 신규 계정 표시 이름을 `빈틈고객`으로 저장한다. 기존 사용자의 이름·역할은 재로그인으로 덮어쓰지 않는다.

프론트 포트가 다르면 `FRONTEND_URL`을 변경한다. 이 값은 경로 없는 origin이다. 로그인 후 이동할 앱 경로를 늘리려면 `AUTH_ALLOWED_RETURN_PATHS=/,/reservations`처럼 정확한 상대 경로를 쉼표로 나열한다. 외부 URL, 쿼리 문자열, 허용 목록 밖의 경로는 400이다.

카카오 화면에서 `KOE004`가 나오면 해당 키가 속한 앱의 **카카오 로그인 → 사용 설정 → 상태 ON**을 확인한다. `KOE006`은 리다이렉트 URI 불일치다. [카카오 설정 안내](https://developers.kakao.com/docs/ko/kakaologin/prerequisite)를 따른다.

## DB 준비

로그인은 기존 [01ddl.sql](../infra/oracle/local-setup/01ddl.sql)의 `user_accounts` 테이블을 사용한다. 테이블·인덱스나 관리자 계정을 새로 설계하지 않는다. 해당 SQL을 아직 적용하지 않았다면 **개발 DB의 BINTIME_APP 연결**에서 `1. USER` 절의 `CREATE TABLE user_accounts (...)`만 한 번 실행한다. 이미 테이블이 있으면 다시 실행하지 않는다. 다른 도메인의 테이블을 로그인 구현 때문에 초기화할 필요는 없다.

```sql
SELECT USER FROM dual;
SELECT table_name FROM user_tables WHERE table_name = 'USER_ACCOUNTS';
```

`ddl-auto=validate`를 유지하고 자동 테이블 생성·삭제는 하지 않는다. 로그인 저장소는 기존 Oracle DDL과 맞는 짧은 SQL을 `JdbcClient`로 실행한다. 앱 DB 계정에는 기존 CREATE SESSION/TABLE 권한 외에 추가 관리자 권한이 필요하지 않다.

## 브라우저에서 확인

```bash
./gradlew bootRun
```

1. 브라우저 주소창에서 `http://localhost:8080/api/v1/auth/oauth/kakao/start`를 연다. 프론트도 `window.location.assign(백엔드주소 + '/api/v1/auth/oauth/kakao/start')`로 이동한다. Swagger의 Try it out 또는 fetch로 OAuth 리다이렉트를 따라가는 방식은 사용하지 않는다.
2. 카카오 로그인·동의 후 프론트로 303 이동한다. 브라우저에는 HttpOnly `bt_session` 쿠키만 전달하고 공급자 토큰은 전달·보관하지 않는다. `state` 검증 실패·동의 거절·토큰 오류는 프론트 `/login?error=OAUTH_FAILED`로 이동한다.
3. 프론트에서 `GET /api/v1/auth/csrf`를 `credentials: 'include'`로 호출한다. 성공 시 `success.data.csrfToken`을 보관한다.
4. `POST /api/v1/auth/logout`을 같은 쿠키와 `X-CSRF-Token` 헤더로 호출한다. 성공은 204이고 이후 CSRF 조회는 401이다.

카카오 콜백은 성공 시 `code`·`state`, 동의 거절 등 실패 시 `error`·`state`와 선택적인 `error_description`을 받는다. 따라서 Swagger에서 이 query들을 일괄 필수로 선언하지 않는다. `code`와 `error`는 각 성공·실패 경로에서 전달되는 대체 값이며, 누락되거나 잘못된 `state`로는 인증이 성립하지 않는다. 실제 콜백 처리는 Spring Security가 담당하고 실패 시 기존 `/login?error=OAUTH_FAILED`로 이동한다.

로그아웃의 `X-CSRF-Token`은 Swagger에서도 필수 헤더다. 누락·불일치는 Spring Security가 403 `CSRF_INVALID`로 처리한다. 컨트롤러의 헤더 바인딩이 optional인 것은 MVC의 400 응답보다 이 보안 검증 결과를 일관되게 사용하기 위해서다.

```javascript
const API = 'http://localhost:8080';
const response = await fetch(`${API}/api/v1/auth/csrf`, { credentials: 'include' });
if (!response.ok) throw new Error('로그인이 필요합니다.');
const { success } = await response.json();
await fetch(`${API}/api/v1/auth/logout`, {
  method: 'POST',
  credentials: 'include',
  headers: { 'X-CSRF-Token': success.data.csrfToken },
});
```

개발 중 프론트와 백엔드 주소는 모두 `localhost`로 통일한다. `127.0.0.1`과 섞으면 쿠키 호스트가 달라진다. Swagger는 별도 로그인 없이 열리며, 같은 백엔드 origin에서 먼저 로그인하면 브라우저 쿠키를 사용한다. 쿠키를 Authorize 입력란에 직접 붙이지 않는다. `GET /api/v1/users/me`는 로그인하지 않으면 401, 로그인한 상태에서는 DB의 본인 프로필을 반환한다. 사용자 API용 DB 마이그레이션을 먼저 적용한다.

## 다른 도메인에서 사용자 확인

Spring Security의 인증 주체는 카카오 ID 대신 내부 사용자 ID와 DB 역할을 담는다. 인증이 필요한 API를 구현할 때 URL 인증 규칙·권한·CSRF 적용도 함께 추가한다. 현재는 `/api/v1/auth/**`, `/api/v1/users/**`만 적용했고, 나머지 계약 골격은 종전처럼 501이다.

```java
public ResponseEntity<?> example(@AuthenticationPrincipal BintimePrincipal principal) {
  String userId = principal.userId();
  // 요청 body의 userId 대신 인증 주체의 userId로 본인 데이터를 조회한다.
}
```

신규 가입은 CUSTOMER로 생성한다. OWNER 권한은 기존 DB 역할을 읽으며 카카오 프로필이나 요청 파라미터로 승격하지 않는다. 동시 첫 로그인은 기존 `uq_user_kakao` UNIQUE 제약으로 한 계정에 합쳐진다.

## 배포

서버 `.env`에 동일한 키를 설정하고 `KAKAO_REDIRECT_URI`는 `https://140.238.19.165/api/v1/auth/oauth/kakao/callback`, `FRONTEND_URL`은 실제 프론트 origin으로 지정한다. `compose.yaml`이 이 값들을 앱에 전달한다. 카카오 Developers에도 배포 콜백을 추가한다. 배포 DB에 `user_accounts`가 있는지 별도로 확인한다. 이 작업은 서버 배포나 Git push를 자동 실행하지 않는다.

배포는 기존 Nginx CORS 허용 목록을 사용하고 Spring에서 중복 CORS 헤더를 추가하지 않는다. 프론트 주소가 바뀌면 Nginx 허용 목록도 맞춘다. 로컬 쿠키는 SameSite=Lax, 배포는 HTTPS 전용 Secure + SameSite=None이다. 현재 Vercel 프론트와 IP 기반 API는 서로 다른 사이트라 브라우저의 서드파티 쿠키 차단 정책에 영향을 받을 수 있다. 실제 시연 브라우저에서 확인하고, 차단될 경우 프론트와 같은 사이트의 프록시 또는 도메인으로 API를 연결한다.

<!-- shortcut: 단일 앱 인스턴스의 HttpSession을 사용한다. 다중 인스턴스나 재기동 후 세션 유지가 필요해지면 Spring Session Redis로 전환한다. -->
세션은 현재 단일 앱의 메모리에 있고 유휴 30분에 만료된다. 서버 재시작 후에는 다시 로그인한다. 로그아웃에 연결된 SSE·WebSocket 종료는 해당 기능을 구현할 때 연결해야 한다.

## 검증

`./gradlew test`는 실제 HTTP 서버·쿠키와 H2를 사용해 로그인, 재로그인, 동시 가입, state 위조·재사용, 동의 거절, CSRF·로그아웃, returnTo, CORS를 검증한다. 외부 카카오 토큰·사용자 정보 API만 대체하므로 실제 카카오 계정·키 없이 실행된다. 실제 키의 권한·동의 화면은 브라우저로 별도 확인한다.

참고: [카카오 REST 로그인](https://developers.kakao.com/docs/ko/kakaologin/rest-api), [Spring Security OAuth 로그인](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html).
