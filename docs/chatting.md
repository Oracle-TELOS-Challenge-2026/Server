# 매장 채팅·예약 제안

고객과 매장 사장님이 문의하는 채팅이다. AI 예약 조건 분석과는 별개다. 일반 텍스트와 사장님이 작성하는 예약 제안 카드를 지원하며 이미지는 첨부하지 않는다. 텍스트 채팅은 [DB 계약 v1](chatting-db.md)의 네 테이블을 사용한다. **예약 제안에는 추가 마이그레이션 `08-chat-booking-proposals.sql`이 필요하다.** 기존 채팅·더미데이터를 초기화하지 않으며 운영 DB 적용은 별도로 진행한다.

## REST

로그인한 카카오 세션 `bt_session`을 사용한다. 변경 요청에는 `/api/v1/auth/csrf`에서 받은 `X-CSRF-Token`도 보낸다. POST의 `Idempotency-Key`는 최대 200 UTF-8 바이트이며 같은 작업을 재시도할 때 유지한다.

| API | 동작 |
| --- | --- |
| `POST /api/v1/chatting/rooms` | `{ "storeId": "..." }`. CUSTOMER·OWNER 모두 다른 사장님의 매장에 고객으로 생성 가능. 본인 매장은 생성 불가. 고객+매장당 한 방, 신규 201·기존 200 |
| `GET /api/v1/chatting/rooms?limit=20&cursor=...` | 본인 방만 최근 활동순. `nextCursor`를 그대로 다음 요청에 사용 |
| `GET /api/v1/chatting/rooms/{id}/messages?limit=30&beforeSequence=...` | 최근 이력을 화면용 오름차순으로 반환. 이전 페이지는 `nextBeforeSequence` 사용 |
| `POST /api/v1/chatting/rooms/{id}/messages` | `{ "clientMessageId": "UUID", "content": "문의 내용" }`. 신규 201·기존 메시지 200 |
| `PUT /api/v1/chatting/rooms/{id}/read` | `{ "lastReadSequence": 3 }`. 뒤로 이동하지 않으며 최신 sequence 초과는 400 |
| `POST /api/v1/chatting/rooms/{id}/booking-proposals` | 사장님이 예약 제안 카드를 생성. 201. 본문은 아래 예약 제안 계약 참고 |
| `POST /api/v1/chatting/rooms/{id}/booking-proposals/{proposalId}/confirm` | 방의 고객이 `{ "expectedVersion": 1 }`로 확정. 200 |

목록·이력 limit은 1~100이다. 메시지는 공백만으로 구성할 수 없고 최대 2000자다. 사용자·방·매장·clientMessageId는 DB 계약의 최대 64 UTF-8 바이트를 따른다. 타인의 방은 404이며 `READ_ONLY` 방은 이력·읽음 처리는 가능하지만 새 메시지는 409다.

HTTP 멱등 키는 최초 응답 데이터와 HTTP 상태를 24시간 재생한다. 같은 키로 다른 본문을 보내면 409다. 메시지의 `(방, 발신자, clientMessageId)` 중복 제거는 24시간 이후에도 유지되며 다른 내용으로 재사용하면 409다. REST와 WS에서 같은 clientMessageId를 재사용하면 한 번만 저장된다. HTTP 멱등 재생은 최초 상태(201 포함)를 유지하므로 새 키로 기존 메시지를 요청한 경우의 200과 구분한다.

## 브라우저 연결 예시

프론트 `FRONTEND_URL`에서 로그인한 뒤 실행한다. 먼저 REST로 채팅방을 생성하고 반환된 `id`를 사용한다. 쿠키는 브라우저가 WebSocket handshake에 자동으로 포함한다.

```javascript
const api = "http://localhost:8080"; // 운영에서는 HTTPS API 주소
const roomId = "REST로 생성한 방 ID";
const response = await fetch(`${api}/api/v1/auth/csrf`, { credentials: "include" });
const { success } = await response.json();
const socket = new WebSocket(
  `${api.replace(/^http/, "ws")}/api/v1/chatting/ws`,
  "bintime.chat.v1",
);
const send = (type, payload) => socket.send(JSON.stringify({
  type, requestId: crypto.randomUUID(), payload,
}));
socket.onopen = () => send("connection.authenticate", {
  csrfToken: success.data.csrfToken,
});
socket.onmessage = ({ data }) => {
  const frame = JSON.parse(data);
  if (frame.type === "connection.ready") {
    send("room.subscribe", { roomId, afterSequence: 0 });
  }
  console.log(frame);
};
// connection.ready 이후 전송. 응답 유실 시 같은 clientMessageId로 재시도한다.
// send("message.send", { roomId, clientMessageId: crypto.randomUUID(), content: "안녕하세요" });
// send("room.read", { roomId, lastReadSequence: 1 });
```

연결 후 5초 안에 인증한다. 4401·4403 또는 세션 무효화에 따른 1008 종료는 로그인·CSRF 상태부터 확인하고 무한 재시도하지 않는다. 프레임 스키마·오류·heartbeat·제한은 [실시간 계약](contracts/realtime.md#채팅-websocket--c-ws)에 정의한다. Swagger의 Try it out은 REST 전용이다.

재연결 때 해당 방에서 연속으로 받은 마지막 sequence를 `afterSequence`로 전송한다. `room.subscribed`의 `replayThroughSequence`까지 재생한 뒤 실시간 메시지를 받는다. `room.resync_required`의 `reason=MESSAGE_GAP`, `subscriptionActive=false`이면 구독되지 않은 상태이므로 REST 이력을 페이지로 조회·병합하고 최신 sequence로 다시 구독한다. `reason=PROPOSAL_STATE_LIMIT`, `subscriptionActive=true`이면 기존 구독을 유지하며 REST로 카드 상태만 보완하고 같은 연결에서 재구독하지 않는다. 서버 이벤트는 HTTP 공통 응답 봉투 없이 `{type, requestId, payload}`다. 본인 메시지는 ACK·이벤트 양쪽으로 올 수 있으므로 ID로 합친다.

현재 한 앱 컨테이너에서 동작한다. 재시작 시 메시지 원장은 유지되고 연결은 재구독해야 한다. 다중 앱 간 브로드캐스트, 첨부 파일, 채팅 푸시는 구현 범위에 포함하지 않는다. Nginx의 기존 Upgrade 설정을 사용하며 별도 외부 포트를 열지 않는다.

## 예약 제안 — C-06·C-07

프런트가 고객 메시지의 예약 의도를 감지해 사장님에게 작성 버튼을 보여준다. 별도 감지·AI API는 없다. 사장님이 시술·추가 옵션, 날짜·시간, 소요 시간, 최종 금액을 확인하고 전송할 때 C-06을 호출한다. 제안 전송만으로 실제 예약이나 시간 선점이 발생하지 않는다.

두 API 모두 기존 로그인 세션, `X-CSRF-Token`, `Idempotency-Key`를 사용한다. 생성과 확정에는 각각 별도 키를 사용하고 해당 요청을 재시도할 때는 같은 키·본문을 유지한다. 성공 데이터와 최초 HTTP 상태를 24시간 재생한다. 같은 키의 다른 본문은 `409 IDEMPOTENCY_KEY_REUSED`다.

### 사장님 제안 생성

```http
POST /api/v1/chatting/rooms/{roomId}/booking-proposals
Content-Type: application/json
X-CSRF-Token: ...
Idempotency-Key: ...
```

```json
{
  "sourceMessageId": "msg_customer_001",
  "serviceId": "svc_gel",
  "serviceDescription": "젤 네일 + 기존 젤 제거 + 아트 2개",
  "startAt": "2026-10-20T14:00:00+09:00",
  "durationMinutes": 90,
  "price": 75000,
  "waitlistId": null
}
```

- 해당 매장의 현재 사장님만 생성할 수 있다. `sourceMessageId`는 해당 방 고객의 가장 최근 `TEXT` 메시지여야 한다. 사장님·다른 방·예약 카드 메시지를 원본으로 사용하지 않는다.
- `serviceId`는 해당 매장의 활성 시술이다. `serviceDescription`은 공백 제외 내용이 있는 1~1000자 문자열이며 시술·추가 옵션을 함께 적는다.
- `startAt`은 오프셋을 포함한다. `durationMinutes`는 15~480분의 5분 단위, `price`는 0~10,000,000원의 정수다. `endAt`은 서버에서 시작 시각과 소요 시간으로 계산한다. 실제 예약 구간은 `[startAt, endAt)`이다.
- 등록 시술의 기본 가격·시간과 별도로 제안의 설명·시간·금액을 저장한다. 나중에 등록 시술이 변경되어도 제안 내용을 클라이언트 값이나 변경된 기본값으로 교체하지 않는다.
- `waitlistId`는 선택이다. 지정한 경우 고객 확정 시 기존 예약 대기 충족 규칙을 적용한다.
- 새 제안 생성 시 같은 방의 이전 미확정 제안은 `SUPERSEDED`가 되어 확정할 수 없다. 생성은 `201`과 `ChatMessageResponse`를 반환하고 `message.created`로 전파한다.
- 신규 텍스트 전송과 제안 생성은 사용자별 합산 초당 5건 한도를 사용한다. 초과하면 `429 RATE_LIMITED`이며 중복 요청의 성공 재생은 횟수를 소모하지 않는다.

### 고객 확정

```http
POST /api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm
Content-Type: application/json
X-CSRF-Token: ...
Idempotency-Key: ...
```

```json
{ "expectedVersion": 1 }
```

해당 방 고객만 `PENDING` 제안을 확정한다. 금액·설명·시각을 확정 요청에서 다시 받지 않으며 서버에 저장된 제안으로 Store Port를 호출한다. 영업시간, 활성 시술, 고객의 기존 예약, 매장 자원의 시간 중복과 대기 조건을 확정 시점에 다시 검사한다. 충돌하면 예약과 제안 모두 변경하지 않는다. 생성된 예약은 기존 고객 예약 목록·사장님 일정·취소 API에서 동일하게 처리된다.

예약 생성과 제안의 `CONFIRMED`·`reservationId` 갱신은 한 트랜잭션이다. 성공하면 `200`과 갱신된 카드 메시지를 반환하고 `message.updated`로 전파한다. 동일 키 재시도는 최초 응답을 재생하며 예약을 추가 생성하지 않는다. 이미 확정된 제안을 다른 키로 다시 확정하면 `409 PROPOSAL_ALREADY_CONFIRMED`다.

### 카드 응답·상태

기존 `ChatMessageResponse`에 nullable `bookingProposal`이 추가된다. `TEXT`이면 null, `BOOKING_PROPOSAL`이면 아래 정보를 포함한다. 고객에게 카드를 표시할 때 `content` 대신 `bookingProposal`의 저장된 값을 사용한다.

```json
{
  "id": "msg_proposal_001",
  "roomId": "room_001",
  "sequence": 2,
  "sender": { "id": "usr_owner", "role": "OWNER", "displayName": "네일 모먼트" },
  "clientMessageId": "proposal_001",
  "type": "BOOKING_PROPOSAL",
  "content": "예약 제안: 젤 네일 + 기존 젤 제거 + 아트 2개",
  "createdAt": "2026-10-17T14:00:00+09:00",
  "bookingProposal": {
    "id": "proposal_001",
    "sourceMessageId": "msg_customer_001",
    "serviceId": "svc_gel",
    "serviceDescription": "젤 네일 + 기존 젤 제거 + 아트 2개",
    "startAt": "2026-10-20T14:00:00+09:00",
    "endAt": "2026-10-20T15:30:00+09:00",
    "durationMinutes": 90,
    "price": 75000,
    "status": "PENDING",
    "version": 1,
    "reservationId": null,
    "waitlistId": null
  }
}
```

| status | 화면 의미·후속 처리 |
| --- | --- |
| `PENDING` | 고객 확인 대기. 현재 버전으로 확정 가능 |
| `CONFIRMED` | 예약 확정. `reservationId`로 기존 예약 기능 사용 |
| `SUPERSEDED` | 새 제안으로 대체됨. 확정 불가 |
| `REVIEW_REQUIRED` | 제안 이후 새 고객 TEXT가 저장되어 요청 변경 가능성 있음. 사장님의 새 제안 필요 |

`CONFIRMED`는 이 제안으로 예약이 확정되었다는 기록이다. 예약이 나중에 취소·완료되어도 제안 카드를 현재 예약 상태로 취급하지 않는다. `reservationId`로 기존 예약 조회 API를 호출해 현재 상태를 확인한다.

고객의 새로운 `TEXT` 메시지가 저장되면 해당 방의 `PENDING` 제안을 `REVIEW_REQUIRED`로 바꾼다. 문구의 예약 의도를 서버에서 추측하지 않으며 고객의 모든 신규 텍스트에 적용한다. 동일 `clientMessageId` 또는 HTTP 멱등 키의 재전송은 신규 메시지가 아니므로 무효화를 반복하지 않는다. 상태 변경마다 `version`이 증가한다.

이력 조회와 방 목록의 마지막 카드에는 저장된 최신 상태가 반영된다. 제안 상태 변경은 메시지를 새로 추가하지 않으므로 카드의 `message.id`·`sequence`는 유지된다. 활성 구독에는 전체 카드가 `message.updated`로 전달된다. 구독 시에는 과거 메시지의 최신 제안 상태도 `message.updated`로 다시 동기화한다. 같은 메시지·제안 ID를 upsert하고 **더 낮은 `bookingProposal.version`은 무시**한다. 특히 HTTP 멱등 재생은 최초의 과거 상태를 반환할 수 있다.

`message.updated`는 새 메시지가 아니므로 unread 수를 늘리거나 마지막 메시지 sequence를 뒤로 이동시키지 않는다. `afterSequence`는 생성 메시지의 연속 수신 위치로 유지한다. 동기화 대상 제안이 1000개를 넘으면 `room.resync_required`에 `reason=PROPOSAL_STATE_LIMIT`, `subscriptionActive=true`가 오며 **실시간 구독은 유지된다.** 저장한 카드 캐시의 상태를 REST 이력으로 다시 확인한다. 캐시가 전체 과거 카드를 보관한다면 전체 페이지를 조회해 병합해야 한다. 이 경우 같은 연결에서 재구독하지 않으며 상태 확인 중 수신한 최신 이벤트는 version으로 병합한다. 재연결 상세는 [실시간 계약](contracts/realtime.md#채팅-예약-제안-상태-동기화)를 따른다.

### 오류 코드

아래 코드는 공통 오류 봉투의 `error.code`로 구분한다. 409 오류가 오면 같은 확정 요청을 자동으로 반복하지 말고 최신 카드를 조회한다.

| HTTP | code | 의미 |
| --- | --- | --- |
| 403 | `OWNER_REQUIRED` / `CUSTOMER_REQUIRED` | 제안 생성·확정의 행위자 권한이 맞지 않음 |
| 404 | `SOURCE_MESSAGE_NOT_FOUND` | 원본이 해당 방 고객의 TEXT 메시지가 아님 |
| 404 | `PROPOSAL_NOT_FOUND` | 해당 방에 제안이 없음 |
| 409 | `PROPOSAL_SOURCE_OUTDATED` | 원본 이후 새 고객 TEXT가 있어 최근 메시지 기준으로 다시 작성 필요 |
| 409 | `PROPOSAL_SUPERSEDED` | 새 제안으로 대체되어 확정할 수 없음 |
| 409 | `PROPOSAL_REVIEW_REQUIRED` | 고객 요청 변경으로 사장님의 재검토 필요 |
| 409 | `PROPOSAL_VERSION_CONFLICT` | 화면 버전과 서버의 최신 버전이 다름 |
| 409 | `PROPOSAL_ALREADY_CONFIRMED` | 이미 확정된 제안을 새 키로 확정 요청 |
| 409 | `RESERVATION_TIME_CONFLICT` | 고객 또는 매장 자원의 예약 시간 충돌 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 작업·키에 다른 요청 본문 |
| 429 | `RATE_LIMITED` | 신규 텍스트·제안 생성 합산 초당 5건 초과. C-06에 적용 |

기존 `ROOM_NOT_FOUND`, `ROOM_READ_ONLY`, `CSRF_INVALID`, 시술·영업시간·대기 관련 store 오류도 유지한다. 제안 생성은 실제 예약 가능성을 보장하지 않으며 확정 시 실패할 수 있다.
