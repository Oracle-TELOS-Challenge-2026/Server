# 채팅 DB 계약 v1 — 더미데이터 전달용

2026-10-16의 텍스트 채팅 기반 계약이다. 고객과 매장 사장님의 1:1 문의 채팅이며, AI 검색 대화와 분리한다. 로컬 Oracle Free와 OCI Autonomous DB 모두 아래 기반 스키마를 사용한다. REST·WebSocket 처리는 구현되었으며 **예약 제안 확장은 하단의 추가 계약과 `08-chat-booking-proposals.sql`을 따른다.** 기존 운영 DB에 추가 변경을 자동 적용하지 않는다.

반영 확인: 이 작업 PC의 로컬 `BINTIME_APP`와 OCI 공유 `BINTIME_APP`에 적용 완료했다. 채팅 4개 테이블의 컬럼 25개·명명된 제약조건 22개·애플리케이션 인덱스 13개가 일치한다. 격리된 Oracle 스키마에서 신규 생성과 기존 메시지 보존·재실행을 검증했고, 검증용 데이터는 남기지 않았다. 다른 PC의 로컬 DB에도 아래 변경 SQL을 적용한다.

## 적용 순서

- **기존 DB:** 앱 계정 연결에서 [004-chatting.sql](../infra/oracle/migrations/004-chatting.sql)을 실행한다. 채팅 쓰기가 없는 상태에서 적용하며 재실행할 수 있다. 기존 채팅과 다른 도메인의 데이터는 유지한다.
- **새 빈 DB:** [01ddl.sql](../infra/oracle/local-setup/01ddl.sql)에 채팅 v1이 포함되어 있다. 이후 `004-chatting.sql`을 실행해도 중복 생성하지 않는다. 사용자·검색·FCM의 별도 마이그레이션은 기존 안내를 따른다.
- **검증:** [verify-chatting.sql](../infra/oracle/verify-chatting.sql)을 실행한다. 기존 더미데이터의 방 순번·참여자·읽음 위치·내용 길이를 검사하고, 임시 사용자·매장·채팅을 생성해 제약조건을 확인한 뒤 모두 롤백한다. 실제 더미데이터를 생성하는 파일이 아니다.
- DataGrip에서는 `SET ...`과 `WHENEVER ...`를 제외하고 실행한다. `DECLARE ... END;`는 한 블록으로 실행하며 `/`는 SQL*Plus 블록 구분자다. 초기 DDL을 기존 DB에 재실행하거나 `ddl-auto=update`로 맞추지 않는다.

## 고정한 테이블·컬럼

ID는 기존과 동일하게 `VARCHAR2(64 BYTE)`다. 시각은 모두 `TIMESTAMP(6) WITH TIME ZONE`이며, 예제는 `+09:00`을 사용한다.

### `chatting_rooms` — 고객+매장당 1개

| 컬럼 | 타입 | 필수·기본값 | 의미 |
| --- | --- | --- | --- |
| `room_id` | `VARCHAR2(64 BYTE)` | PK, 자동 GUID 가능 | 채팅방 ID |
| `customer_id` | `VARCHAR2(64 BYTE)` | NOT NULL, FK → `user_accounts.user_id` | 고객 ID |
| `store_id` | `VARCHAR2(64 BYTE)` | NOT NULL, FK → `store_shops.store_id` | 매장 ID |
| `status_code` | `VARCHAR2(9 BYTE)` | NOT NULL, `OPEN` | `OPEN` 또는 `READ_ONLY` |
| `last_sequence` | `NUMBER(16,0)` | NOT NULL, `0` | 마지막 저장 메시지 순번 |
| `last_message_at` | 시각 | NULL 가능 | `last_sequence` 메시지의 서버 저장 시각. 빈 방은 NULL |
| `created_at` | 시각 | NOT NULL, `SYSTIMESTAMP` | 방 생성 시각 |

`(customer_id, store_id)`는 UNIQUE다. 사장님 ID를 별도로 복사하지 않고 `store_shops.owner_user_id`로 찾는다. 방 목록의 활동 시각은 `COALESCE(last_message_at, created_at)`이며, 같은 시각은 `room_id`로 순서를 정한다.

### `chatting_messages` — 기존 컬럼 유지

| 컬럼 | 타입 | 필수·기본값 | 의미 |
| --- | --- | --- | --- |
| `message_id` | `VARCHAR2(64 BYTE)` | PK, 자동 GUID 가능 | 메시지 ID |
| `room_id` | `VARCHAR2(64 BYTE)` | NOT NULL, FK → 채팅방 | 소속 방 |
| `sender_id` | `VARCHAR2(64 BYTE)` | NOT NULL, FK → 사용자 | 해당 고객 또는 현재 매장 사장님 |
| `message_sequence` | `NUMBER(16,0)` | NOT NULL | 방별 1부터 증가하는 순번 |
| `client_message_id` | `VARCHAR2(64 BYTE)` | NOT NULL | 클라이언트가 재전송 때 유지하는 ID |
| `content_text` | `CLOB` | NOT NULL | 일반 텍스트. API 구현 시 1~2000자 검증 |
| `created_at` | 시각 | NOT NULL, `SYSTIMESTAMP` | 서버 저장 시각 |

UNIQUE는 `(room_id, message_sequence)`, `(room_id, sender_id, client_message_id)`다. REST·WS는 같은 중복 방지 규칙을 사용한다. `type=TEXT`는 현재 유일한 메시지 유형이므로 별도 타입 컬럼·첨부 테이블은 만들지 않는다. 발신자 이름은 `user_accounts`에서, 역할은 방의 고객/매장 소유자 관계에서 구한다.

### `chatting_read_positions` — 사용자별 마지막 읽음 위치

| 컬럼 | 타입 | 필수·기본값 | 의미 |
| --- | --- | --- | --- |
| `room_id` | `VARCHAR2(64 BYTE)` | 복합 PK, FK → 채팅방 | 방 ID |
| `user_id` | `VARCHAR2(64 BYTE)` | 복합 PK, FK → 사용자 | 읽은 참여자 |
| `last_read_sequence` | `NUMBER(16,0)` | NOT NULL, `0` | 마지막 읽은 순번 |
| `read_at` | 시각 | NULL 가능 | 읽음 갱신 시각. 순번이 1 이상이면 필수 |

PK는 `(room_id, user_id)`다. 행이 없으면 `lastReadSequence=0`, `readAt=null`로 해석한다. 더미데이터에서 읽음 상태가 필요할 때만 넣어도 된다. 참여자 등록용 테이블이 아니며 이 행만으로 방 접근 권한을 부여하지 않는다.

### `chatting_idempotency` — HTTP 요청 재처리 기록

| 컬럼 | 타입 | 필수·기본값 | 의미 |
| --- | --- | --- | --- |
| `actor_id` | `VARCHAR2(64 BYTE)` | 복합 PK, FK → 사용자 | 요청 사용자 |
| `operation` | `VARCHAR2(200 BYTE)` | 복합 PK | `POST:` + 실제 요청 경로 |
| `key_value` | `VARCHAR2(200 BYTE)` | 복합 PK | `Idempotency-Key` |
| `fingerprint` | `VARCHAR2(64 BYTE)` | NOT NULL, 길이 64 | 정규화한 요청 본문의 SHA-256 hex |
| `result_json` | `CLOB` | NOT NULL, STRICT JSON | 성공 응답의 `success.data` 스냅샷 |
| `http_status` | `NUMBER(3,0)` | NOT NULL, `200` 또는 `201` | 최초 성공 상태 코드 |
| `expires_at` | 시각 | NOT NULL | 최초 성공 후 24시간 |

`operation` 예: `POST:/api/v1/chatting/rooms`, `POST:/api/v1/chatting/rooms/room_dummy_001/messages`. HTTP 키의 만료와 관계없이 메시지의 `client_message_id` 중복 방지는 메시지를 보존하는 동안 유지한다. **더미데이터 담당자는 이 테이블을 비워 둔다.** 런타임이 저장할 기록이다.

## 더미데이터 입력 순서와 예제

1. 기존 방식으로 `user_accounts`의 고객·사장님, `store_shops`의 매장을 준비한다. 사장님은 `store_shops.owner_user_id`와 일치해야 한다.
2. 채팅방을 넣고 메시지를 1, 2, 3… 순서대로 넣는다. 모든 INSERT에 컬럼 목록을 명시한다.
3. 방의 `last_sequence`를 메시지 최댓값으로, `last_message_at`을 그 메시지의 `created_at`으로 맞춘다.
4. 필요하면 고객·사장님의 읽음 위치를 넣고 COMMIT한다. `chatting_idempotency`에는 넣지 않는다.

아래 고객·사장님·매장 ID는 **이미 준비한 실제 더미 ID로 교체**한다. 방·메시지 ID는 예제마다 고유하게 지정한다. 이 예제는 한 번만 실행한다.

```sql
INSERT INTO chatting_rooms(room_id,customer_id,store_id,created_at)
VALUES('room_dummy_001','usr_customer_dummy','shop_dummy',
  TO_TIMESTAMP_TZ('2026-10-16 14:59:00 +09:00','YYYY-MM-DD HH24:MI:SS TZH:TZM'));

INSERT INTO chatting_messages(message_id,room_id,sender_id,message_sequence,client_message_id,content_text,created_at)
VALUES('msg_dummy_001','room_dummy_001','usr_customer_dummy',1,'dummy_client_001',
  '내일 오후 예약 가능한가요?',TO_TIMESTAMP_TZ('2026-10-16 15:00:00 +09:00','YYYY-MM-DD HH24:MI:SS TZH:TZM'));

INSERT INTO chatting_messages(message_id,room_id,sender_id,message_sequence,client_message_id,content_text,created_at)
VALUES('msg_dummy_002','room_dummy_001','usr_owner_dummy',2,'dummy_client_002',
  '네, 오후 4시에 가능합니다.',TO_TIMESTAMP_TZ('2026-10-16 15:01:00 +09:00','YYYY-MM-DD HH24:MI:SS TZH:TZM'));

UPDATE chatting_rooms SET last_sequence=2,
  last_message_at=(SELECT created_at FROM chatting_messages WHERE message_id='msg_dummy_002')
WHERE room_id='room_dummy_001';

INSERT INTO chatting_read_positions(room_id,user_id,last_read_sequence,read_at)
VALUES('room_dummy_001','usr_customer_dummy',1,
  TO_TIMESTAMP_TZ('2026-10-16 15:00:00 +09:00','YYYY-MM-DD HH24:MI:SS TZH:TZM'));

INSERT INTO chatting_read_positions(room_id,user_id,last_read_sequence,read_at)
VALUES('room_dummy_001','usr_owner_dummy',2,
  TO_TIMESTAMP_TZ('2026-10-16 15:01:00 +09:00','YYYY-MM-DD HH24:MI:SS TZH:TZM'));
COMMIT;
```

빈 방은 `last_sequence=0`, `last_message_at=NULL`로 둔다. 읽기 전용 시나리오는 `status_code='READ_ONLY'`로 만들며 기존 이력은 유지한다. 읽지 않은 개수는 **본인이 보내지 않은 메시지 중 본인 읽음 순번보다 큰 메시지 수**로 계산한다. 별도 `unread_count` 컬럼은 없다.

## 후속 구현에서 지킬 규칙

- sequence·읽음 위치는 JavaScript 안전 정수 `0~9007199254740991`, 메시지는 `1~9007199254740991`이다.
- 메시지 저장 시 방 행을 `SELECT ... FOR UPDATE`로 잠그고 `last_sequence` 증가·메시지 INSERT·`last_message_at` 갱신을 한 트랜잭션으로 커밋한다. 커밋 후 ACK·실시간 전달을 한다.
- `READ_ONLY`에서는 신규 메시지 전송을 거절하고 이력 조회와 읽음 갱신은 허용한다.
- 읽음 순번은 감소시키지 않고 방의 `last_sequence`를 넘기지 않는다. 발신자·읽음 사용자는 방의 고객 또는 해당 매장 소유자여야 한다. 이런 행 간 규칙·권한과 메시지 1~2000자 제한은 서비스에서 검증하며 DB 트리거로 구현하지 않는다.
- 동일 `client_message_id`+동일 내용은 기존 메시지, 다른 내용은 409다. HTTP 키 역시 같은 요청은 기존 결과를 반환하고 다른 요청 본문은 409로 거절한다. 만료된 HTTP 키만 재사용할 수 있다.
- 스키마 변경이 다시 필요하면 `004-chatting.sql`을 수정하지 않고 다음 번호의 마이그레이션과 변경 안내를 추가한다. 더미데이터 투입 후 테이블 재생성·데이터 초기화는 하지 않는다.

WebSocket 프레임과 재연결 계약은 [실시간 전송 계약](contracts/realtime.md)을 따른다.

## 예약 제안 추가 계약 — `08-chat-booking-proposals.sql`

예약 제안 기능에는 [08-chat-booking-proposals.sql](../infra/oracle/migrations/08-chat-booking-proposals.sql)이 추가로 필요하다. 기존 `004-chatting.sql`과 이미 들어간 채팅·더미데이터를 유지한다. 새 테이블을 추가하고 기존 예약에 제안 시술 설명을 보존할 컬럼을 추가하며 테이블 재생성·초기화는 하지 않는다. **SQL 파일 추가와 운영·공유 OCI 반영은 별개다.** 로컬·OCI 각각 현재 연결 대상을 확인한 뒤 배포 전에 같은 변경을 적용한다.

### 제안 데이터

`chatting_booking_proposals`는 다음 정보를 보존한다. 구체적인 타입·제약조건·인덱스는 추가 SQL을 기준으로 한다.

| 정보 | 의미 |
| --- | --- |
| 제안 ID·방 ID·카드 메시지 ID | 기존 메시지 하나에 대응하는 제안. 같은 카드의 상태만 갱신 |
| 원본 고객 메시지 ID | 생성 시 해당 방의 가장 최근 고객 TEXT. 사장님·다른 방 메시지는 불가 |
| 시술 ID·시술 및 추가 옵션 설명 | 등록 시술 ID와 사장님이 확인한 설명 스냅샷 |
| 시작·종료 시각·소요 시간·최종 금액 | 제안한 실제 값. 등록 시술의 변경된 기본값과 별개 |
| 상태·version | `PENDING`, `CONFIRMED`, `SUPERSEDED`, `REVIEW_REQUIRED`. 상태 변경 시 버전 증가 |
| 예약 ID | 미확정이면 null. CONFIRMED이면 같은 트랜잭션에서 생성한 실제 예약 ID |
| 대기 ID | 선택. 확정 시 기존 대기 충족 규칙에 전달 |
| 생성·갱신 시각 | 생성 시각은 연결된 카드 메시지, 상태 갱신 시각은 제안에 저장 |

기존 메시지 컬럼을 초기화하지 않는다. 해당 카드 메시지와 제안 데이터를 연결해 `type=BOOKING_PROPOSAL`과 최신 `bookingProposal`을 반환한다. 연결된 제안이 없는 기존 메시지는 계속 `TEXT`다. 예약 제안 생성은 메시지를 하나 추가하므로 room sequence·활동 시각·읽음 규칙을 그대로 사용한다. 제안 상태 변경은 기존 카드 갱신이므로 메시지 순번을 추가하지 않는다.

### 트랜잭션·더미데이터 규칙

- 기존 사용자 → 채팅방 잠금 순서에서 신규 제안 생성과 이전 미확정 제안의 SUPERSEDED 전환을 함께 처리한다.
- 고객의 신규 TEXT 저장과 PENDING 제안의 REVIEW_REQUIRED 전환을 함께 처리한다. 메시지 중복 재전송은 이를 반복하지 않는다.
- 고객 확정은 저장된 값으로 Store Port를 호출해 영업시간·고객·매장 자원의 충돌과 대기를 검증하고, 예약 생성과 제안 CONFIRMED·예약 ID 갱신을 하나의 트랜잭션으로 커밋한다.
- `chatting_idempotency`의 기존 24시간 성공 응답 재생을 사용한다. 이 테이블에 더미 응답을 넣지 않는다.
- 일반 텍스트 더미는 기존 입력 순서를 그대로 따른다. 예약 제안 더미는 API를 통해 만드는 편이 안전하다. 직접 넣는 경우 실제 방 참여자, 원본 고객 TEXT, 카드 메시지, 시술, 제안, 확정 예약 간 관계를 모두 맞춘다. 확정 예약 없이 CONFIRMED 제안만 넣지 않는다.
- 최신 카드 복원은 조회 시 조인과 WebSocket 구독 시 상태 동기화로 처리한다. 과거 `result_json`은 최초 성공 당시 스냅샷이므로 최신 상태의 기준으로 사용하지 않는다.

시술 설명·시간·금액을 변경하려면 제안 데이터를 직접 수정하지 말고 새 제안을 생성한다. 예약 취소는 기존 예약 API가 담당하며 CONFIRMED 제안의 `reservationId`로 연결한다. 세부 REST·프런트 병합 규칙은 [채팅 예약 제안](chatting.md#예약-제안--c-06c-07)을 따른다.
