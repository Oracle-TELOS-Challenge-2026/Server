# Store API 구현 및 운영 계약

ST-01~17과 사장님 등록 API 3개를 Oracle 원장에 구현한다. Controller → Service → Repository 구조이며 예약, 대기 완료, 멱등 응답, 후속 작업 저장은 같은 DB 트랜잭션에 참여한다. 결제, 실제 매칭·알림 발송, 로그인은 이 구현 범위에 포함하지 않는다.

## 등록과 API 응답

기존 URL·필드·성공 상태는 유지한다. 예약 생성과 아래 등록 API는 `201`, 나머지는 `200`이며 공통 `ApiResponse<T>`를 반환한다.

| API | 요청 | 결과 |
| --- | --- | --- |
| `POST /api/v1/owner/stores` | name, category, address, location(latitude/longitude), description?, imageUrl?, openingHours | StoreDetailResponse |
| `POST /api/v1/owner/stores/{storeId}/services` | name, durationMinutes, basePrice, active | StoreServiceResponse |
| `POST /api/v1/owner/stores/{storeId}/slots` | serviceId, startAt | ReservationSlotResponse |

등록 API는 OWNER 권한과 `Idempotency-Key`가 필수다. 소유자는 인증 정보에서 결정한다. 매장별 소유자 1명, 동시 예약 자원 1개이며 사장님 응답의 자원 ID는 `resource_{storeId}`다. 공개 슬롯과 고객 예약 응답에 내부 자원·고객 ID를 노출하지 않는다. 초기 평점은 평균·개수 모두 0이며 매장 시간대는 `Asia/Seoul`이다.

시술 소요시간은 15~480분, 5분 단위이며 가격은 0~10,000,000원 정수다. 일반 슬롯은 등록 당시 시술 가격·소요시간을 저장한다. 이후 가격·시술명을 변경해도 예약 스냅샷과 기존 슬롯 가격은 유지한다. 시술 비활성화는 해당 시술 슬롯의 신규 예약을 막는다.

영업시간은 MON~SUN 각 1개씩 7개를 제출한다. 휴무일은 opensAt/closesAt가 null, 영업일은 `HH:mm`이고 opensAt < closesAt다. 자정을 넘는 영업은 지원하지 않는다. 일반 슬롯 전체가 영업시간 안에 들어가야 한다. 영업시간 변경은 신규 일반 슬롯에 적용하며 기존 공급·예약을 자동 취소하지 않는다.

PATCH에서 생략한 필드는 유지하고 명시적 null은 `400 NULL_PATCH_FIELD`로 거절한다. 소개 삭제는 빈 문자열을 제출한다. Oracle에서는 빈 문자열이 NULL로 저장되므로 이후 조회에서는 description=null일 수 있다. 매장·시술 수정은 expectedVersion이 필수이며 가격·상태 변경에도 버전을 증가시킨다.

## 예약 상태와 잠금

외부 슬롯 상태는 `AVAILABLE / BOOKED / BLOCKED / EXPIRED`다. 이 MVP는 별도 HELD 상태·선점 유지 시간 없이 하나의 트랜잭션에서 `AVAILABLE → BOOKED`로 확정한다. 예약 상태는 `CONFIRMED → CANCELED / COMPLETED / NO_SHOW`만 허용한다. 기존 AGENTS.md의 일반 HELD 흐름에 대한 store MVP의 구체화다.

쓰기는 요청자 계정/고객 잠금 → 매장 잠금 → 대상 슬롯/예약 → search 대기 순서로 처리한다. 취소·완료는 매장 잠금 안에서 처리하므로 다른 고객의 계정 잠금을 역순으로 잡지 않는다. 고객별 잠금은 다른 매장에 대한 동시 중복 예약도 막는다. 매장당 자원이 1개이므로 동일 매장 쓰기를 직렬화한다. 확인된 슬롯의 DB 유일 인덱스도 중복 확정을 방어한다.

예약 시 미래 AVAILABLE 슬롯, 활성 시술, expectedSlotVersion, expectedPrice를 다시 검증한다. 예약의 시간 구간은 `[시작, 종료)`이며 고객과 매장의 CONFIRMED 예약에 겹칠 수 없다. 앞 예약 종료와 다음 예약 시작이 같으면 허용한다. 일반 슬롯 등록·빈자리 발행도 다른 공개 공급과 CONFIRMED 예약에 겹칠 수 없다.

완료는 종료 시각부터, 노쇼는 시작 10분부터 가능하다. 종료한 CONFIRMED 예약도 취소할 수 있지만 빈자리는 만들지 않는다. 취소/노쇼 후 남은 시간이 있으면 원예약당 최대 하나의 DRAFT 빈자리를 만든다. 일반 원슬롯은 자동 재판매하지 않는다. 예약된 빈자리는 이후 취소되어도 해당 발행 이력은 FILLED로 유지하고 새 빈자리를 만든다.

## 다른 도메인의 예약 호출

다른 도메인은 [StoreReservationPort](../src/main/java/com/bintime/store/contract/StoreReservationPort.java)를 생성자 주입받아 `reserve(Command)`를 호출한다. Spring 구현체는 [StoreReservationAdapter](../src/main/java/com/bintime/store/adapter/StoreReservationAdapter.java)이며 기존 `StoreApplicationService.reserve`의 검증·잠금·멱등·이벤트 로직을 재사용한다. HTTP 요청 컨텍스트, store의 Controller·DTO·엔티티에 의존할 필요가 없다. 현재 생성과 확정은 한 작업이므로 별도 confirm 호출은 제공하지 않는다.

호출 도메인은 인증과 사용자 확인을 마친 본인의 `userId`만 전달해야 한다. 포트는 인증 토큰을 검증하는 경계가 아니며 사용자 존재와 현재 이름·역할은 user 공개 서비스로 조회한다. 외부 요청 본문의 임의 userId를 그대로 전달하면 안 된다. 매칭·알림만으로 자동 예약하지 않는다.

```java
var reservation = reservations.reserve(new StoreReservationPort.Command(
    authenticatedUserId, idempotencyKey, slotId, expectedPrice, expectedSlotVersion, waitlistId));
```

- `userId`: 인증된 사용자 ID. 존재하지 않거나 비어 있으면 `401 UNAUTHENTICATED`.
- `idempotencyKey`: 필수 ASCII 1~200자. HTTP ST-06과 같은 사용자·RESERVE 키 공간 및 24시간 정책을 공유한다. 채널을 바꾼 재시도도 같은 키와 요청을 사용하며, 다른 요청에 키를 재사용하면 `409 IDEMPOTENCY_KEY_REUSED`다.
- `slotId`, `expectedPrice`, `expectedSlotVersion`: 필수. 가격은 사용자가 확인한 KRW 정수 원, 버전은 최신 슬롯 조회 값이다. 가격·버전·가용성·시간 중복 검증은 HTTP와 동일하다.
- `waitlistId`: 선택. 전달하면 대기 소유권·활성 상태·조건을 검증하고 예약과 함께 완료한다.

반환값 `ConfirmedReservation`은 예약·매장·시술·슬롯 ID, `CONFIRMED` 상태, 시술 시작·종료, 정가·예약 금액, 연결 대기 ID와 예약 버전을 담는다. 시각은 오프셋을 포함하며 시술 구간은 `[startAt, endAt)`다. 멱등 재생은 최초 성공 스냅샷을 반환한다.

포트는 기본 REQUIRED 트랜잭션으로 실행하며 호출자 트랜잭션이 있으면 참여한다. 최종 확정은 해당 트랜잭션 커밋 시점이다. 예약·슬롯·대기 완료·멱등 기록·후속 이벤트는 함께 커밋/롤백한다. 호출자는 대기/매장 잠금을 먼저 잡고 호출해 기존 고객 → 매장 → 대기 잠금 순서를 뒤집지 않아야 한다. 외부 AI·전송 작업을 같은 트랜잭션에서 기다리지 않는다.

업무 실패는 기존 `ApiBusinessException`의 `status`·`code`로 전달한다. 대표 오류는 `SLOT_NOT_FOUND`(404), `SLOT_EXPIRED`, `SLOT_UNAVAILABLE`, `SERVICE_INACTIVE`, `VERSION_CONFLICT`, `PRICE_CHANGED`, `RESERVATION_TIME_CONFLICT`(409) 및 대기 검증 오류다. 실패는 성공 멱등 기록을 남기지 않으므로 요청을 수정해 재시도할 수 있다. 트랜잭션 안에서 실패를 잡아 계속 진행하면 rollback-only가 될 수 있으므로 재시도는 새 트랜잭션에서 수행한다.

## 빈자리 발행과 만료

내부적으로 기존 store_slots에 일반 슬롯과 빈자리를 함께 저장한다. DRAFT는 외부 슬롯 BLOCKED, AVAILABLE은 빈자리 PUBLISHED, BOOKED는 FILLED, CLOSED는 WITHDRAWN으로 표현한다. vacancyId와 발행 slotId는 같은 원장 ID다.

발행할 시술은 같은 매장의 활성 시술이어야 한다. 미래 시작부터 시술 종료까지 전체가 availableFrom~availableUntil 안에 들어가야 한다. 원시술·원가격·원구간은 재발행으로 바뀌지 않는다. 변경한 영업시간 때문에 기존 빈 구간을 자동 제거하지 않는다.

PERCENT는 0~100의 정수이며 `정가 × (100-할인율) / 100`의 원 미만을 버린다. FIXED_PRICE는 할인 후 결제 금액으로, 0원부터 정가까지 허용한다. 가격은 매장 입력으로만 결정한다. discountPercent는 등록한 정률 값을 그대로 반환하며 FIXED_PRICE 발행은 null이다. 원 단위 버림으로 역산한 다른 할인율을 만들지 않는다. 철회 후 유효 구간이 남으면 다시 발행할 수 있으며 publicationVersion은 발행마다 증가한다. 예약된 빈자리 철회는 `409 VACANCY_ALREADY_FILLED`다.

AVAILABLE 슬롯은 시작 시각부터 예약할 수 없고 EXPIRED로 전환한다. 미발행·철회 빈자리는 남은 구간 종료 시 만료된다. 읽기 API도 경계 시각을 검증하며 만료 작업은 매장 잠금 아래 같은 규칙으로 저장 상태와 이벤트를 정리한다. 기본 실행 주기는 60초다. `bintime.store.expiration-enabled=false`로 작업을 끌 수 있다.

## 목록과 타임테이블

기본 페이지 크기는 20, 최대는 100이다. 커서는 필터 해시·정렬값·ID를 포함하며 다른 사용자/필터에서 재사용하거나 형식이 틀리면 400이다. 슬롯은 시작·ID 오름차순, 예약은 시작·ID 내림차순, 빈자리는 발생 시각·ID 내림차순, 시술은 ID 오름차순이다. 슬롯 날짜 범위는 매장 시간대 기준 양끝 포함 최대 31일이다. 시각은 오프셋을 가진 ISO 8601이며 Oracle 정밀도에 맞춰 마이크로초 단위로 저장한다.

예약 목록은 `/api/v1/reservations/me?scope=UPCOMING|PAST|CANCELED`다. UPCOMING에는 진행 중인 CONFIRMED 예약도 포함하며 종료 시각 이후·완료·노쇼는 PAST다. counts는 커서/상태 필터를 적용하기 전 해당 사용자의 전체 예약을 기준으로 계산한다. 빈자리 counts도 해당 매장 전체를 기준으로 계산한다. awaitingPublication은 DRAFT와 유효한 WITHDRAWN을 합산한다.

타임테이블은 해당 날짜와 겹치는 예약 및 공급을 반환한다. 예약·공급 원장 합계가 500개를 넘으면 일부 결과를 정상 응답으로 자르지 않고 `409 TIMETABLE_LIMIT_EXCEEDED`를 반환한다.

## 대기 연동

`search.service.WaitlistReservationService`가 대기 소유권·동의한 활성 상태·매장·시술·카테고리·시술 전체 시간·가격·거리 조건을 검증한다. store는 search 테이블을 직접 수정하지 않는다.

기존 `search_waitlists`는 해당 행을 잠가 FULFILLED로 변경한다. 작업 중 추가된 search 구현의 `search_intent` 대기도 search의 사용자 잠금과 공개 `WaitlistService.fulfilled` 메서드를 통해 같은 트랜잭션에서 처리하며 확정 예약 ID와 후속 벡터 작업도 함께 기록한다. 이후 예약/작업 저장이 실패하면 대기 완료도 롤백된다.

두 저장 형식의 ID를 지원하기 위해 예약의 기존 search_waitlists 전용 FK를 제거한다. waitlistId 유일성은 유지하며 공개 협력 서비스에서 소유권·상태를 검증한다. 이 변경은 기존 데이터를 삭제하지 않는다. search의 다른 연동 계약과 실제 인증·검색·알림 연결은 [search.md](search.md)를 따른다.

## 멱등성과 오류

Idempotency-Key는 공백 없는 ASCII 1~200자다. 동일 사용자·작업·키에 대한 최초 성공의 상태와 DTO를 24시간 재사용한다. 요청 본문과 대상 리소스도 비교하므로 같은 작업·키로 다른 매장/슬롯/예약을 요청하면 `409 IDEMPOTENCY_KEY_REUSED`다. 실패한 트랜잭션은 성공 기록을 남기지 않으며 같은 키로 수정·재시도할 수 있다. 공통 응답 meta는 현재 요청에서 생성한다.

입력 오류 400, 미인증 401, 권한 부족 403, 리소스 없음 404, 버전·가격·시간·상태 충돌 409를 사용한다. 대표 오류는 VERSION_CONFLICT, PRICE_CHANGED, SLOT_UNAVAILABLE, SLOT_EXPIRED, RESERVATION_TIME_CONFLICT, WAITLIST_FORBIDDEN, WAITLIST_INACTIVE, WAITLIST_CONDITION_MISMATCH다. Swagger에는 각 API에서 발생하는 오류를 선언한다.

## 후속 작업 계약

발행·철회·예약·취소·완료·노쇼·만료를 store_events에 업무 트랜잭션과 함께 기록한다. matchingStatus=QUEUED는 매칭 의뢰의 영속 기록이 저장되었다는 뜻이다. 실제 소비자는 아직 연결하지 않으며 작업을 임의로 PROCESSED로 바꾸지 않는다.

행에는 event_id, event_type, slot_id, publication_version, slot_version, payload_json, status_code, created_at가 있다. payload는 eventId, type, storeId, slot, occurredAt를 포함한다. slot은 당시 DTO 스냅샷이며 publicationVersion과 version을 포함한다. 이벤트 종류는 PUBLISHED, WITHDRAWN, BOOKED, CANCELED, COMPLETED, NO_SHOW, EXPIRED다.

소비자는 eventId를 처리 키로 중복을 무시하고, 슬롯별 publicationVersion과 slot.version을 함께 비교해 오래된 작업을 무시해야 한다. 마감 작업을 받은 뒤 같은 발행 버전의 늦게 도착한 PUBLISHED로 재개방하지 않는다. 외부 전송의 재시도·작업 임대·실제 알림 전송은 소비자 연결 작업에서 구현한다. 대기 잠금을 잡은 상태로 store의 쓰기 잠금을 역순으로 잡지 않는다.

## 인증과 배포

store는 카카오 로그인 `bt_session` 쿠키와 선택적으로 설정한 Bearer JWT를 사용한다. 자세한 API별 적용 여부, CSRF 및 JWT 설정은 [store 인증 계약](store-auth.md)을 참고한다. 과거 `bintime.auth.production-enabled` 플래그와 `bintime.csrf-token` 세션 속성은 사용하지 않는다.

테스트 또는 명시적 로컬 개발에서만 `bintime.auth.local-enabled=true`와 X-Local-User-Id를 사용할 수 있다. local/test 프로필이 필요하며 prod가 함께 활성화되면 무시한다. 역할·이름은 헤더가 아닌 기존 user_accounts에서 읽고, 대체 식별로 계정을 자동 생성하지 않는다. 기본 local 프로필도 이 옵션은 꺼져 있다.

신규 빈 DB에는 [초기 SQL](../infra/oracle/local-setup/01ddl.sql)을, 기존 초기 스키마에는 [변경 SQL](../infra/oracle/migrations/02-store-api.sql)을 1회 적용한다. 두 SQL을 같은 스키마에 연속 적용하지 않는다. 기존 매장의 영업시간은 알 수 없으므로 7일 휴무로 보정하며 사장님이 수정한 뒤 새 일반 슬롯을 등록한다. 기존 예약의 시술명·시간·가격은 보존하고 누락한 시술 ID·소요시간·고객 표시 이름을 보정한다. 과거 발행 시각을 알 수 없으면 기존 슬롯 created_at를 사용한다.

Oracle DDL은 암묵적으로 커밋하므로 백업·쓰기 중단 후 SQL을 적용하고 애플리케이션을 배포한다. ddl-auto=validate를 유지한다. 새 search 테이블을 사용하는 배포는 [search SQL](sql/search-oracle.sql)도 적용해야 한다.

## 검증

SearchStoreCatalog의 원장 조회와 검색 버전은 [카탈로그 계약](store-catalog.md)을 따른다. 기존 store API DB에는 [06-store-search-catalog.sql](../infra/oracle/migrations/06-store-search-catalog.sql)을 별도로 적용해야 한다. 최신 초기 SQL에는 포함되어 있다. 자동 적용은 하지 않는다.

```sh
./gradlew format
./gradlew test
./gradlew build
# 로컬 Oracle 컨테이너의 새 테스트 스키마에서만 검증한다.
python3 infra/oracle/verify-store.py
```

StoreApiTests는 20개 API 흐름·입력·권한·버전·스냅샷·멱등성·커서·시간 경계·대기 완료/롤백과 동시 요청을 검증한다. Oracle 검증 스크립트는 기존 초기 스키마의 데이터 보존과 변경 SQL을 검증한 뒤 신규/변경 스키마 모두 ddl-auto=validate와 같은 API·경합 테스트를 실행한다. 테스트 계정은 BT_STORE_ 접두사를 가지며 앱 계정은 변경하지 않는다. 생성한 테스트 스키마는 결과 확인을 위해 남긴다. 초기 스키마가 이미 Git HEAD에 변경된 뒤에는 STORE_BASELINE_SQL에 변경 전 SQL 파일을 지정한다. 포트/컨테이너 변경은 STORE_ORACLE_PORT/STORE_ORACLE_CONTAINER로 지정한다.

직접 API를 눌러보는 QA는 [로컬 QA 서버·Postman 실행 안내](../qa/store/README.md)를 참고한다. 격리된 Oracle 스키마와 36개 순차 요청을 제공한다.
