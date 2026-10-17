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

`text`는 선택적인 자유문 취향 원문이며 0~2,000자 문자열이다. 길이는 JavaScript의 `string.length`와 같은 UTF-16 기준이며 일부 이모지는 2자로 센다. 명시적인 빈 문자열 `""`은 원문을 지우고, 필드 생략은 기존 원문을 유지해 이전 클라이언트와 호환한다. 명시적 `null`, 숫자·객체 등 문자열 외 값, 길이 초과는 400이다. 앞뒤 공백을 포함한 입력 원문을 보존한다. 저장과 같은 트랜잭션으로 사용자 벡터 갱신 작업을 등록하며, API 요청 안에서 OpenAI를 호출하거나 벡터 생성 완료를 저장 성공 조건으로 삼지 않는다. 구조화된 배열·가격은 여전히 요청대로 교체하므로 프론트는 취향 원문만 편집할 때도 GET으로 받은 기존 배열·가격과 최상위 버전을 함께 보낸다.

벡터 생성은 저장된 `preferences_json` 전체를 OpenAI Embeddings에 전달하며 기본 모델 `text-embedding-3-small`의 1,536차원 결과를 Oracle `search_user_vector`에 사용자 ID로 저장한다. 사용자 원본 테이블에 벡터 컬럼을 중복 추가하지 않는다. 동일 입력·모델·차원의 벡터는 재사용하고 변경된 선호에 이전 벡터를 사용하지 않는다. OpenAI 일시 장애는 worker에서 재시도하며 저장된 선호는 유지한다. 생성이 완료될 때까지 추천은 기존 거리순 대체 정책을 따른다.

운영에는 `OPENAI_API_KEY`와 `SEARCH_SCHEDULING_ENABLED=true`(기본값)가 필요하다. 200 응답은 벡터 생성 완료를 보장하지 않는다. `search_work`의 VECTOR 작업 상태와 `search_user_vector`의 model·dimensions·source_version·vector_json 저장 여부로 확인한다. 입력은 UTF-8 8,000바이트 제한을 따르며 초과 입력이나 잘못된 키 등 영구 오류는 작업 실패로 확인한다.

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

## 맞춤 빈자리 알림 동의 (U-10/U-11)

현재 시연은 `recommendation_opt_in=N/Y`, 동의 시각 유무와 무관하게 CUSTOMER/OWNER 전체를 맞춤 알림 후보로 조회한다. 특정 매장·예약 대기·사용자 프로필 위치는 필요 없다. 실제 접수·FCM 제출에는 현재 계정, 활성 로그인 세션의 FID 구독, 유효한 미래 VACANCY, 선호 벡터 코사인 유사도 기준(기본 0.5 이상)이 필요하다. 선호가 없거나 유사도가 낮으면 보내지 않는다. 기존 WAITLIST 경로의 시간·가격·거리 등 조건은 유지한다.

1. 로그인하고 선호도를 저장한다. `OPENAI_API_KEY`와 search worker가 필요하다.
2. `POST /api/v1/notifications/push-subscriptions`로 현재 기기의 FID를 등록한다.
3. 등록과 같은 트랜잭션에서 기존 발행 빈자리의 PERSONALIZE 탐색 작업을 접수한다. 새 빈자리 발행·선호 변경도 탐색을 수행한다.

기존 계정이나 신규 기본 N 계정을 Y로 일괄 변경할 필요가 없다. 수신 중단은 기존 `DELETE /api/v1/notifications/push-subscriptions/{subscriptionId}`를 사용한다. 해당 기기 구독을 해제하므로 채팅 푸시에도 적용된다. 재배포·로그아웃 뒤에는 로그인과 FID 등록을 다시 수행한다.

U-10 GET / U-11 PUT `/api/v1/users/me/notification-preferences`는 하위 호환을 위해 유지하지만 OpenAPI에서 deprecated로 표시한다. 응답 `personalizedVacancyEnabled`는 기존 DB 저장값이며 실제 발송 가능 여부가 아니다. false/N이어도 후보에 포함하고, PUT false로 저장해도 발송을 차단하지 않는다. true 저장 시 기존 빈자리 재탐색은 유지한다. 세션·CSRF·공유 expectedVersion/409 검증은 기존과 동일하다.

기존 [008 마이그레이션](../infra/oracle/migrations/008-personalized-notifications.sql)의 컬럼·체크 제약은 유지하며 이번 정책 변경을 위한 추가 SQL은 없다. 컬럼은 Oracle CHAR(1)이고 JPA도 SqlTypes.CHAR로 매핑한다. ddl-auto=validate는 유지한다.
