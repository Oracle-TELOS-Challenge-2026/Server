# Store API 직접 QA

실제 Spring 서버와 Oracle을 대상으로 Postman에서 요청·응답을 직접 확인한다. 로그인 대신 명시적으로 켠 local 인증 경계와 전용 QA 계정을 사용한다. 서버 코드를 바꾸거나 시간을 조작하지 않는다.

## 1. QA 서버 시작

Java 21 이상, Python 3, 실행 중인 로컬 Oracle 컨테이너와 기존 로컬 `.env`가 필요하다. Redis 설정도 기존 `.env`를 사용한다. 저장소 루트에서 실행한다.

```sh
# 인프라가 꺼져 있을 때 먼저 실행
# docker compose -f compose.dev.yaml up --detach --wait --wait-timeout 600
python3 qa/store/start.py
```

스크립트가 최신 JAR을 빌드하고 **새 Oracle QA 스키마**에 초기 SQL·search SQL·QA 계정을 준비한 뒤 실제 앱을 실행한다. 기존 앱 계정의 테이블·데이터는 변경하지 않는다.

- 주소: `http://localhost:18081`
- Swagger: `http://localhost:18081/swagger-ui/index.html`
- 프로필: local
- 대체 인증: `bintime.auth.local-enabled=true`
- search 백그라운드 작업은 꺼 두고 store 만료 작업은 실제로 실행한다.
- 종료: 실행한 터미널에서 Ctrl+C. QA 데이터는 Oracle에 남아 확인할 수 있다.
- 다시 실행하면 새 QA 스키마를 만든다. 스키마 이름과 접근 권한 0600의 DB 연결 파일 경로를 시작 로그에 출력하며 비밀번호는 출력하지 않는다.
- 포트 변경: `python3 qa/store/start.py --port 18082`. Postman의 baseUrl도 같은 포트로 바꾼다.

서버의 `Started BintimeApplication` 로그가 나오면 다음 단계로 진행한다. Swagger 공개 조회는 바로 실행할 수 있다. 보호 API의 로컬 사용자 헤더는 아래 Postman 컬렉션에 이미 설정했다.

## 2. Postman에서 직접 요청

1. Postman의 Import로 [Store-QA.postman_collection.json](Store-QA.postman_collection.json)을 가져온다.
2. 컬렉션 Variables의 baseUrl이 `http://localhost:18081`인지 확인한다. 같은 이름의 환경 변수가 있다면 이 값을 덮어쓰지 않도록 제거하거나 맞춘다.
3. **01부터 순서대로** Send를 누른다. 응답 Body와 Test Results를 함께 확인한다. 또는 컬렉션 Runner로 전체를 실행한다.
4. 01은 실행 ID와 **한국 시간 기준 내일 10시**를 설정한다. 생성된 매장·시술·슬롯·예약·빈자리 ID와 버전은 응답 스크립트가 다음 요청에 자동으로 전달한다.

처음부터 다시 QA하려면 01부터 다시 실행한다. 새로운 실행 ID·매장을 사용하므로 이전 실행의 예약과 멱등 키가 충돌하지 않는다. 실행 도중 매장/예약의 상태를 수동으로 바꾸었다면 이후 요청의 버전·예상 결과가 달라질 수 있다.

| 단계 | 직접 확인할 동작 | 예상 결과 |
| --- | --- | --- |
| 01~08 | 매장·시술·일반 슬롯 등록 및 공개 조회 | 생성 201, 조회 200, AVAILABLE |
| 09~13 | 잘못된 가격, 정상 예약, 멱등 재전송, 변경된 키 내용, 중복 예약 | 409 / 201 / 동일 예약 201 / 409 / 409 |
| 14~19 | 내 예약·상세·사장님 타임테이블·타인의 접근 | 본인/소유자 200, 타인 403 |
| 20~22 | 매장 소개·시술 정가 변경 | 수정 200, 기존 예약은 50,000원 유지 |
| 23~24 | 고객 취소와 빈자리 조회 | CANCELED, DRAFT |
| 25~27 | 30% 할인 발행 → 철회 → 재발행 | 42,000원, WITHDRAWN, publicationVersion=2 |
| 28~29 | 고객 B 재예약과 발행 철회 시도 | CONFIRMED, 예약된 빈자리 철회 409 |
| 30~31 | 완료·노쇼를 너무 일찍 요청 | 409 STATUS_TOO_EARLY |
| 32~34 | 미인증·명시적 null·잘못된 버전 | 401 / 400 / 409 |
| 35~36 | 최종 고객 목록과 슬롯 상태 | 고객 A CANCELED, 할인 슬롯 BOOKED |

**의도한 400/401/403/409도 정상 QA 결과**다. HTTP 상태만 보지 말고 Test Results가 통과하는지, error.code가 설명과 같은지 확인한다.

할인 발행의 matchingStatus=QUEUED는 DB에 의뢰를 저장했다는 뜻이다. 이 QA에서 실제 매칭이나 알림이 발송되지는 않는다. 대기 연결과 동시 요청의 원자성은 StoreApiTests/Oracle 검증 스크립트에서 별도로 검증한다.

## 3. 완료·노쇼·만료의 실제 성공 확인

기본 컬렉션은 내일 예약을 만들기 때문에 30~31에서는 조기 거절을 확인한다. 시간을 기다리지 않고 완료/노쇼 성공을 반환하게 만드는 QA 우회 기능은 제공하지 않는다.

실제 시간 경과를 보고 싶다면 다음처럼 진행한다.

1. 01~05를 실행한 뒤 컬렉션 Variables의 slotStartAt을 **가까운 미래의 영업시간**으로, qaDate를 같은 날짜로 변경한다. 예: `2026-10-16T20:00:00+09:00` / `2026-10-16`. 예시 시각은 그대로 쓰지 말고 실행일 기준 미래로 정한다.
2. 06부터 순서대로 실행한다. 슬롯 등록 시점부터 취소·발행·재예약까지 끝날 여유를 둔다. 60분 시술 전체가 09:00~22:00에 들어가야 한다.
3. 28의 재예약 뒤 해당 예약에 대해 아래 둘 중 하나를 실행한다. **같은 예약에서 완료와 노쇼를 모두 성공시킬 수는 없다.**

| 동작 | 가능 시점 | 요청 |
| --- | --- | --- |
| 노쇼 | 시작 10분 이후 | 31을 복제하고 Tests를 아래 성공 검증으로 교체. expectedVersion은 최신 예약 버전 |
| 완료 | 종료 시각 이후 | 30을 복제하고 Tests를 아래 성공 검증으로 교체. expectedVersion은 최신 예약 버전 |

복제한 요청의 Tests 스크립트를 통째로 아래처럼 바꾼다. 완료 요청은 마지막 값만 `COMPLETED`로 바꾼다.

```javascript
pm.test("상태 변경 성공", function () {
  pm.response.to.have.status(200);
  pm.expect(pm.response.json().success.data.reservation.status).to.eql("NO_SHOW");
});
```

성공 시 reservation.status는 NO_SHOW 또는 COMPLETED다. 노쇼 시 시간이 남으면 vacancy가 DRAFT로 생성되며 availableFrom은 처리 시각이다. 완료 시 vacancy는 null이다. 30~31의 기존 Tests는 조기 거절용이므로 성공 시나리오에 맞게 수정한다. 실패 요청은 멱등 성공 기록을 남기지 않지만, 복제 요청에는 새 Idempotency-Key를 쓰면 구분하기 쉽다.

만료는 **예약하지 않은 별도 슬롯**으로 확인한다. 06을 복제해 새 Idempotency-Key와 미래 startAt으로 등록하고, 시작 시각이 지난 뒤 08의 slotId를 해당 ID로 바꿔 조회한다. status=EXPIRED, bookable=false여야 한다. 60초 주기 작업 또는 상태 조회가 저장 상태를 정리한다.

## 4. 문제 원인 구분

| 현상 | 확인 |
| --- | --- |
| 접속 거절 | 서버 시작 완료·포트·Postman baseUrl |
| 401 UNAUTHENTICATED | X-Local-User-Id 헤더, local 프로필, local-enabled 옵션, QA 계정 존재 |
| 403 OWNER_REQUIRED / STORE_FORBIDDEN | 사장님 계정으로 호출했는지, 해당 매장 소유자인지 |
| 409 IDEMPOTENCY_KEY_REUSED | 같은 작업·키로 본문/대상을 바꾸었는지. 새 키 사용 또는 01부터 새 실행 |
| 409 VERSION_CONFLICT | 응답의 최신 version 사용. 예약 요청은 슬롯 버전, 취소/상태 변경은 예약 버전 |
| 409 SLOT_EXPIRED | startAt이 이미 지났는지. 서버는 실제 시간을 사용 |
| 409 VACANCY_TIME_MISMATCH | 새 시술 전체가 원래 남은 구간에 들어가는지 |
| 시작 시 ORA/스키마 오류 | 로컬 Oracle 컨테이너 상태·컨테이너 이름·SQL 적용 로그 |

DataGrip에서 DB도 확인하려면 시작 로그의 연결 파일에 있는 DB_URL·DB_USERNAME·DB_PASSWORD로 **QA 스키마**에 연결한다. 아래 조회로 API 응답과 저장 상태를 비교할 수 있다.

```sql
SELECT store_id, name, owner_user_id, version_no FROM store_shops;
SELECT slot_id, source_type, status_code, original_price, payable_price,
       publication_version, version_no FROM store_slots;
SELECT reservation_id, user_id, slot_id, status_code, payable_price, version_no
FROM store_reservations;
SELECT event_type, slot_id, publication_version, status_code FROM store_events;
```

시간 경계를 빠르게 반복하거나 실제 동시 요청을 검사할 때는 다음 검증도 함께 사용한다.

```sh
./gradlew test --tests 'com.bintime.store.StoreApiTests'
python3 infra/oracle/verify-store.py
```
