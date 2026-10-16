# 사용자 프로필·위치·선호도 API

U-04~U-08을 구현한다. 모든 요청은 카카오 로그인으로 발급된 `bt_session` 쿠키를 사용한다. 본인 ID는 서버 세션에서 가져오며 요청 본문으로 사용자 ID나 역할을 바꿀 수 없다.

## DB 준비

기존 사용자 테이블에 버전과 위치 메타데이터 컬럼이 필요하다. 로컬 및 배포 DB 각각의 **BINTIME_APP 연결**에서 [001-user-profile.sql](../infra/oracle/migrations/001-user-profile.sql)을 스크립트로 실행한다. 이 스크립트는 기존 테이블·사용자를 유지하며 재실행할 수 있다. 최초 DB라면 기존 `01ddl.sql`을 한 번 적용한 다음 실행한다. DataGrip에서는 `SET`/`WHENEVER`가 SQL*Plus 지시문이므로 제외하고 `DECLARE ... END; /` 블록과 UPDATE/COMMIT을 실행한다.

- `version_no`: 기존·신규 사용자 모두 1부터 시작.
- `accuracy_meters`, `location_source`, `location_saved_at`: 새 위치 요청의 메타데이터.
- 기존 위치는 관측 시각을 보존하고 출처를 `MANUAL`, 저장 시각을 기존 `location_at`로 보완한다.
- 초기 DDL을 다시 실행하거나 데이터를 삭제하지 않는다. 애플리케이션 시작 시 마이그레이션을 자동 실행하지 않는다.

## API

| 메서드·주소 | 동작 | 성공 |
| --- | --- | --- |
| GET `/api/v1/users/me` | 이름·역할·소유 매장 ID·최근 위치·선호도·버전 조회 | 200 |
| PATCH `/api/v1/users/me` | 이름 수정 | 200, 전체 프로필 |
| PUT `/api/v1/users/me/location` | 최근 위치 저장 | 200, 저장된 위치 |
| DELETE `/api/v1/users/me/location` | 최근 위치 삭제 | 204, 본문 없음 |
| PUT `/api/v1/users/me/preferences` | 구조화 선호도 교체·취향 원문 저장 | 200, 선호도와 새 버전 |

별도 선호도 GET은 추가하지 않는다. `GET /users/me`의 `success.data.preferences`로 조회한다. 초기 위치는 null, 선호도는 빈 배열과 `maxPrice:null`, `text:""`다. 기존 DB의 선호도에 원문이 없어도 `text`는 빈 문자열로 반환한다. 소유 매장 ID는 같은 애플리케이션 DB의 `store_shops.owner_user_id`로 조회하며 매장 정보는 수정하지 않는다.

최초 가입은 `roles:["CUSTOMER"]`다. 매장 등록에 성공하면 `roles:["OWNER"]`로 바뀌고 `ownedStoreIds`에 등록한 매장이 포함된다. OWNER도 고객 기능을 사용할 수 있으므로 프론트는 고객 화면을 CUSTOMER 역할만으로 제한하지 않는다. 매장 등록 후 프로필을 다시 조회하며 재로그인은 필요 없다.

`GET /users/me`의 **최상위 `success.data.version`**을 이름 수정과 선호도 저장의 `expectedVersion`으로 사용한다. `preferences.version`을 별도로 만들지 않는다. 선호도 저장 응답의 `success.data.version`도 같은 사용자 버전이다. 이름·선호도를 수정한 뒤에는 응답의 새 버전으로 갱신한다.

프론트는 모든 요청에 `credentials: 'include'`를 설정한다. 변경 요청에는 `GET /api/v1/auth/csrf`로 받은 `success.data.csrfToken`을 필수 `X-CSRF-Token` 헤더에 보낸다. 누락·불일치는 Spring Security에서 403 `CSRF_INVALID`로 처리한다. JSON 요청은 `Content-Type: application/json`을 사용한다.

## 입력과 충돌 처리

이름 수정:

```json
{"displayName":"빈틈고객","expectedVersion":1}
```

이름은 앞뒤 공백을 제거한 뒤 Unicode 코드 포인트 기준 1~30자다. 프로필의 `version`을 `expectedVersion`으로 보낸다. 다른 요청이 먼저 이름이나 선호도를 바꿨다면 409 `VERSION_CONFLICT`다. 다시 GET으로 조회한 뒤 사용자가 변경 내용을 확인하고 재시도한다.

선호도 저장:

```json
{"categories":["NAIL"],"keywords":["심플","원컬러"],"maxPrice":60000,"text":"심플한 원컬러 네일을 선호해요.","expectedVersion":2}
```

`categories`, `keywords`, `maxPrice`, `expectedVersion`은 모두 필수다. `maxPrice` 키를 생략하면 400, 명시적 null은 상한 없음이다. 금액은 정수 0~10,000,000원이다. 카테고리는 기존 매장 분류 `NAIL`, `HAIR`, `EYELASH`, `SKIN`을 사용하며 키워드는 공백 문자열을 허용하지 않는다. 배열을 비우면 해당 선호를 지운다. 배열의 순서·중복은 입력대로 보존한다. 이름·선호도는 **하나의 사용자 버전**을 공유하며 성공할 때마다 1 증가한다.

`text`는 선택적인 자유문 취향 원문이며 0~2,000자 문자열이다. 길이는 JavaScript의 `string.length`와 같은 UTF-16 기준이며 일부 이모지는 2자로 센다. 명시적인 빈 문자열 `""`은 원문을 지우고, 필드 생략은 기존 원문을 유지해 이전 클라이언트와 호환한다. 명시적 `null`, 숫자·객체 등 문자열 외 값, 길이 초과는 400이다. 앞뒤 공백을 포함한 입력 원문을 보존하고, AI 분석·벡터 추출을 호출하거나 저장 성공 조건으로 삼지 않는다. 구조화된 배열·가격은 여전히 요청대로 교체하므로 프론트는 취향 원문만 편집할 때도 GET으로 받은 기존 배열·가격과 최상위 버전을 함께 보낸다.

위치 저장:

```json
{"latitude":37.5445,"longitude":127.0557,"accuracyMeters":30,"source":"DEVICE","observedAt":"2026-10-16T19:00:00+09:00"}
```

위도 -90~90, 경도 -180~180, 정확도는 생략/null 또는 0 이상의 정수다. 출처는 `DEVICE`/`MANUAL`, 관측 시각은 오프셋을 포함한다. 브라우저에서 사용자 위치 동의를 먼저 받는다.

서버는 관측 시각을 DB의 마이크로초 정밀도로 저장하고, 기존 값보다 오래된 요청은 409 `STALE_LOCATION`으로 거절한다. 동일 시각의 요청은 갱신을 허용한다. 시계 오차의 임의 허용 범위는 추가하지 않았다. `savedAt`은 서버 시각이다. 위치 변경·삭제는 프로필 버전을 바꾸지 않으며 기존 예약 대기의 희망 지역에도 영향을 주지 않는다. 위치가 이미 없을 때 삭제해도 204다.

로그인 세션이 없거나 세션의 사용자가 DB에서 삭제됐다면 401, 변경 요청의 CSRF 토큰이 없거나 틀리면 403, 잘못된 입력은 400이다. 응답은 기존 공통 JSON 봉투를 유지한다.

## 확인 순서

1. DB 마이그레이션 후 서버를 실행하고 카카오 로그인한다.
2. GET 프로필로 초기 정보와 버전을 확인한다.
3. CSRF 토큰을 받아 이름·선호도를 수정하고 응답의 새 버전을 사용한다. 취향 원문을 다시 조회하고, `text` 생략 시 유지·빈 문자열 전송 시 초기화되는지 확인한다.
4. 같은 이전 버전으로 다시 수정하면 409이고 기존 값이 유지되는지 확인한다.
5. 위치 저장 후 GET으로 조회하고, 오래된 관측 시각은 409인지 확인한다.
6. 위치 삭제 후 GET의 location이 null인지 확인한다.

자동화 테스트는 세션·CSRF·사용자 격리·입력 검증·버전 경합·위치 경합을 H2에서 검증한다. Oracle 마이그레이션과 JDBC 타입 호환은 로컬 Oracle에서 별도로 확인한다.
