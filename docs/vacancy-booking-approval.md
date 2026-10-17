# 빈자리 모집: 즉시확정과 사장님 선택

2026-10-17 요청. **채팅 예약 제안과 별개의 빈자리 모집 흐름**이다. 고객이 먼저 신청해도 시간을 선점하지 않는다. 여러 고객이 신청할 수 있고 사장님이 신청 시각순으로 확인해 한 명을 선택한다. 원격 DB 적용·프런트 수정·배포는 이 구현에 포함하지 않는다.

## 모집 발행

기존 `POST /api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish`에 `instantBooking`을 추가한다.

```json
{
  "serviceId": "svc_1",
  "startAt": "2026-10-18T17:00:00+09:00",
  "discount": {"type": "PERCENT", "value": 20},
  "expectedVersion": 1,
  "instantBooking": false
}
```

- `true`: 기존처럼 고객 신청 즉시 예약 확정.
- `false`: 신청을 `PENDING`으로 저장하고 사장님의 선택 후 확정.
- 생략·null은 하위 호환을 위해 `true`다. 기존 데이터와 일반 슬롯도 즉시확정으로 유지한다.
- 발행 후 정책 변경은 기존 철회 → 재발행 절차를 사용한다. 철회 시 기존 대기 신청을 거절해 이전 모집 신청이 새 발행으로 넘어가지 않게 한다.
- 슬롯·빈자리 조회의 `instantBooking`, 검색 결과 `bestSlot.instantBooking`, 대기 제안의 `instantBooking`, 알림 `target.instantBooking`에 정책을 반환한다. 알림 이력은 발행 시점의 정책을 보존한다. 예약할 때는 현재 슬롯 원장이 기준이다.
- 기존 매칭·동의·벡터 유사도·FCM 구독 조건을 유지한다. SSE 알림에도 같은 필드가 포함되고 FCM `data.instantBooking`은 문자열 `"true"` 또는 `"false"`다.

## 고객 신청

기존 `POST /api/v1/reservations`를 사용한다. 서버가 슬롯의 정책을 읽으며 고객이 확정 방식을 덮어쓸 수 없다.

```json
{
  "slotId": "vac_1",
  "expectedPrice": 40000,
  "expectedSlotVersion": 2,
  "waitlistId": "wait_1"
}
```

두 방식 모두 최초 성공은 201이다. `waitlistId`는 선택이다. 기존 세션·CSRF·Idempotency-Key를 유지한다.

| 슬롯 정책 | 예약 응답 status | 슬롯 | 연결 대기 |
| --- | --- | --- | --- |
| 즉시확정 | CONFIRMED | BOOKED | FULFILLED |
| 사장님 선택 | PENDING | AVAILABLE 유지 | ACTIVE 유지 |

- 신청 시에도 서비스·가격·버전·고객의 이미 확정된 일정·연결 대기 자격을 검증한다.
- 여러 고객의 PENDING을 허용하고 같은 고객·슬롯의 중복 PENDING은 `409 RESERVATION_REQUEST_EXISTS`다. 동일 Idempotency-Key 재전송은 최초 성공 스냅샷을 반환한다.
- 고객은 여러 빈자리에 신청할 수 있다. PENDING은 다른 예약의 시간을 막지 않는다. 사장님이 실제 선택할 때 고객 일정 충돌을 다시 검증한다.
- 고객의 UPCOMING 목록에 PENDING을 포함한다. UI는 이를 ‘예약 확정’으로 표시하면 안 된다.

## 사장님 신청 목록·선택

신규 ST-21:

```http
GET /api/v1/owner/stores/{storeId}/reservation-requests?limit=20
```

해당 매장 소유자에게만 `items: OwnerReservationResponse[]`, `page`를 반환한다. PENDING만 **createdAt 오름차순, 같은 시각은 ID 오름차순**으로 조회한다. 다음 페이지는 `cursor`를 사용한다. 각 항목의 slotId로 빈자리별 구분이 가능하다. 먼저 신청한 사람을 반드시 선택할 필요는 없다.

기존 ST-13으로 승인·거절한다.

```http
POST /api/v1/owner/stores/{storeId}/reservations/{reservationId}/status
X-CSRF-Token: ...
Idempotency-Key: ...
```

```json
{"status": "CONFIRMED", "expectedVersion": 1}
```

거절은 `status: "REJECTED"`다. 고객은 승인·거절할 수 없다. 기존 COMPLETED/NO_SHOW는 확정 예약에만 적용한다.

- 승인 시점에 서비스 활성 상태·영업시간·보존한 시간과 금액·고객/매장 중복 예약·대기 자격을 재검증한다.
- 선택한 한 건만 CONFIRMED, 슬롯은 BOOKED로 변경한다. 같은 슬롯의 나머지 PENDING은 REJECTED로 마감한다. 모두 한 트랜잭션이다.
- 선택한 고객의 대기만 FULFILLED가 된다. 선택되지 않은 고객의 대기는 ACTIVE를 유지한다.
- 단일 거절은 해당 신청만 REJECTED로 만들고 다른 신청과 빈자리 공급을 유지한다.
- 동시 승인은 고객·매장 잠금과 기존 확정 슬롯 유일성 제약으로 하나만 성공한다.
- 신청/선택/거절의 별도 새 FCM 이벤트는 추가하지 않는다. 이번 알림 변경은 빈자리 모집 알림의 정책 전달이며 결과는 예약 조회·신청 목록에서 복원한다.

## 만료·철회·상태

| status | 의미 |
| --- | --- |
| PENDING | 사장님 선택 대기, 선점 없음 |
| CONFIRMED | 실제 예약 확정 |
| REJECTED | 사장님 거절, 모집 철회 또는 다른 고객 선택으로 마감 |
| WITHDRAWN | 고객이 승인 전 신청 철회 |
| EXPIRED | 선택되지 않은 채 시술 시작 시각 도달 |
| CANCELED / COMPLETED / NO_SHOW | 기존 확정 예약의 취소·완료·노쇼 |

- PENDING의 승인 기한은 startAt이다. 시작 시각과 같거나 이후면 승인할 수 없다.
- 기존 store 만료 작업과 조회 시 정리로 PENDING을 EXPIRED로 전환한다. 고객 상세·목록, 사장님 신청 목록·시간표 조회에도 반영한다.
- 고객 신청 철회는 기존 예약 취소 API를 사용해 WITHDRAWN으로 처리한다. 새 빈자리를 만들거나 다른 신청을 막지 않는다.
- 고객 CANCELED 탭에 REJECTED / WITHDRAWN / EXPIRED도 포함한다. 기존 counts 키는 유지한다.
- PENDING 고객 allowedActions에는 CANCEL, 사장님에는 APPROVE / REJECT를 제공한다. 기존 액션과 상태도 계속 처리해야 한다. 시간표의 PENDING을 확정 일정이나 차단 시간으로 표시하지 않는다.
- 오류: 409 VERSION_CONFLICT / RESERVATION_STATE_CONFLICT / RESERVATION_REQUEST_EXPIRED / RESERVATION_REQUEST_CHANGED / SLOT_UNAVAILABLE / RESERVATION_TIME_CONFLICT. 기존 가격·서비스·대기 오류와 권한 오류도 유지한다.

## 프런트 인계

1. 사장님 PublishDialog에 ‘즉시확정 / 신청 확인 후 선택’을 추가하고 발행 요청에 boolean을 보낸다.
2. 고객 슬롯·대기 제안·알림에서 정책을 표시한다. false일 때 버튼은 ‘예약 신청’, 성공 안내는 ‘사장님 확인 대기’로 한다.
3. PENDING / REJECTED / WITHDRAWN / EXPIRED 상태와 APPROVE / REJECT 액션을 응답 파서와 고객 예약 내역·사장님 화면에 추가한다.
4. 사장님은 ST-21로 신청순 목록을 불러와 한 명을 선택하거나 개별 거절한다. 성공/409 후 목록·슬롯·예약 내역을 다시 조회한다. 새로고침 후에도 서버 상태를 사용한다.
5. false 모집의 고객 신청을 CONFIRMED와 같은 완료 화면으로 보내지 않는다. 기존 입력 검증과 가격·버전·CSRF·Idempotency-Key를 유지한다.
6. 프런트가 이 계약을 배포하기 전에는 false 모집을 사용하지 않는다. 구버전 파서가 PENDING을 처리하지 못할 수 있다.

## DB·배포 순서

**기존 운영 DB는 새 앱 배포 전에** `infra/oracle/migrations/010-vacancy-booking-approval.sql`을 ADMIN 콘솔에서 전체 한 문장으로 실행한다. 대상은 BINTIME_APP으로 고정한다. 앱 쓰기를 중지하고 백업 후 적용하며 Oracle DDL은 자동 커밋이다. 이 파일은 작성만 했으며 운영에 적용하지 않았다.

- store_slots.instant_booking CHAR(1) DEFAULT 'Y' NOT NULL과 Y/N 체크를 추가한다. Java도 CHAR로 명시해 시작 검증의 타입 불일치를 막는다.
- store_reservations 상태 CHECK를 확장한다.
- 대기 ID 유일성은 실제 확정 이력을 대상으로 유지해 미확정 신청만으로 대기를 소진하지 않는다.
- 동일 고객·슬롯의 PENDING 유일 인덱스와 사장님 신청순 조회 인덱스를 추가한다.
- 기존 확정 슬롯 유일성 인덱스는 유지한다. 데이터·회원·예약을 삭제하지 않는다.
- 새 통합/로컬 설치 DDL에도 같은 정의를 포함한다. 중복 적용이 가능하다.

프런트 계약 준비 → DB SQL 적용·확인 → 백엔드 배포 → 두 고객으로 신청/사장님 선택을 검증한다. 별도 환경변수나 Elasticsearch 전체 재색인은 필요 없다.

## 검증 결과

- 전체 format/build: 테스트 435개 중 429개 통과, 선택 실행 검증 6개 제외.
- H2에서 복수 신청·신청순 페이지·권한·동시 승인·중복 신청·개별 거절·철회·만료·대기 완료 시점·실패 시 전체 롤백을 검증했다.
- 로컬 Oracle의 생성한 임시 스키마에서 기존 마이그레이션 전체 경로와 새 설치 DDL의 동등성, 010 재실행, 예약 API 테스트를 통과했다. 임시 스키마를 삭제하고 검증용으로 켰던 컨테이너를 원래 중지 상태로 복구했다.
- 실제 브라우저 화면·기기 푸시·공유 OCI DB 적용은 수행하지 않았다.

```bash
./gradlew format build --no-daemon
python3 infra/oracle/verify-unified-ddl.py --run-store-tests
```

두 번째 명령은 기존 로컬 Oracle 컨테이너가 실행 중이어야 한다. 생성한 BT_DDL_/BT_STORE_ 임시 스키마만 사용하고 기존 앱 스키마를 초기화하지 않는다.
