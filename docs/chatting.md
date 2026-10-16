# 텍스트 매장 채팅

고객과 매장 사장님이 문의하는 채팅이다. AI 예약 조건 분석과는 별개다. [DB 계약 v1](chatting-db.md)의 네 테이블을 그대로 사용하며 추가 마이그레이션은 없다. 이미지를 첨부하지 않는 일반 텍스트만 지원한다.

## REST

로그인한 카카오 세션 `bt_session`을 사용한다. 변경 요청에는 `/api/v1/auth/csrf`에서 받은 `X-CSRF-Token`도 보낸다. POST의 `Idempotency-Key`는 최대 200 UTF-8 바이트이며 같은 작업을 재시도할 때 유지한다.

| API | 동작 |
| --- | --- |
| `POST /api/v1/chatting/rooms` | `{ "storeId": "..." }`. CUSTOMER만 생성. 고객+매장당 한 방, 신규 201·기존 200 |
| `GET /api/v1/chatting/rooms?limit=20&cursor=...` | 본인 방만 최근 활동순. `nextCursor`를 그대로 다음 요청에 사용 |
| `GET /api/v1/chatting/rooms/{id}/messages?limit=30&beforeSequence=...` | 최근 이력을 화면용 오름차순으로 반환. 이전 페이지는 `nextBeforeSequence` 사용 |
| `POST /api/v1/chatting/rooms/{id}/messages` | `{ "clientMessageId": "UUID", "content": "문의 내용" }`. 신규 201·기존 메시지 200 |
| `PUT /api/v1/chatting/rooms/{id}/read` | `{ "lastReadSequence": 3 }`. 뒤로 이동하지 않으며 최신 sequence 초과는 400 |

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

재연결 때 해당 방에서 연속으로 받은 마지막 sequence를 `afterSequence`로 전송한다. `room.subscribed`의 `replayThroughSequence`까지 재생한 뒤 실시간 메시지를 받는다. `room.resync_required`가 오면 구독되지 않은 상태이므로 REST 이력을 페이지로 조회·병합하고 최신 sequence로 다시 구독한다. 서버 이벤트는 HTTP 공통 응답 봉투 없이 `{type, requestId, payload}`다. 본인 메시지는 ACK·이벤트 양쪽으로 올 수 있으므로 ID로 합친다.

현재 한 앱 컨테이너에서 동작한다. 재시작 시 메시지 원장은 유지되고 연결은 재구독해야 한다. 다중 앱 간 브로드캐스트와 첨부 파일은 구현 범위에 포함하지 않는다. Nginx의 기존 Upgrade 설정을 사용하며 별도 외부 포트를 열지 않는다.

## 채팅 FCM 푸시

Oracle 테이블·컬럼·제약은 변경하지 않는다. 새 메시지가 커밋되면 기존 N-04로 등록한 상대방의 활성 FID에 비동기로 푸시를 보낸다. REST와 WebSocket은 같은 메시지 저장 이벤트를 사용하므로 같은 `clientMessageId` 재전송에 새 푸시를 만들지 않는다. 메시지 발신자에게는 보내지 않는다.

수신자에게 유효한 로그인 세션과 FID 등록이 있어야 한다. 채팅 소켓이 끊겼거나 탭이 백그라운드여도 세션·구독이 유효하면 대상이다. 웹소켓 연결 여부를 읽음으로 간주하지 않는다. 발송 직전과 FCM 인증 갱신 후 현재 방 참여자·읽음 위치·구독을 다시 확인해 이미 읽은 메시지, 소유자가 바뀐 매장의 이전 수신자, 로그아웃·구독 해제한 기기를 제외한다.

FCM 데이터는 모두 문자열이다. 프론트 경로가 확정되기 전에는 클릭 주소 `/chat?roomId=방ID`를 사용한다. 프론트가 해당 경로에서 채팅방을 열어야 하며, 실제 방 데이터 조회에는 기존 세션 인증·참여자 검증을 그대로 적용한다.

```json
{
  "type": "CHAT_MESSAGE",
  "notificationId": "msg_메시지ID",
  "roomId": "room_채팅방ID",
  "messageId": "msg_메시지ID",
  "sequence": "12",
  "url": "/chat?roomId=room_채팅방ID"
}
```

`notification`에는 발신자 이름과 짧은 본문 미리보기를 포함한다. HTTPS 환경에서는 같은 프론트 origin의 클릭 링크도 보낸다. 포그라운드 수신은 Firebase `onMessage`에서 UI에 반영하고, 백그라운드 표시에는 Firebase 서비스 워커와 브라우저 알림 권한이 필요하다. 기본 FID 등록과 페이로드 규격은 [Firebase 웹 시작 안내](https://firebase.google.com/docs/cloud-messaging/web/get-started)와 [HTTP v1 Message](https://firebase.google.com/docs/reference/fcm/rest/v1/projects.messages)를 따른다.

채팅 푸시는 채팅 이력·읽음 위치를 사용하며, 매칭 전용 `notification_alerts` 원장과 N-01 알림 목록에는 추가하지 않는다. 새 발송 API나 새 구독 API도 만들지 않는다. 기존 매칭 푸시의 `/customer/waitlist` 링크와 발송 흐름은 유지한다.

현재 로그인 세션과 동일한 단일 앱 범위에서, 대기·발견·기기별 재시도 작업을 합쳐 최대 512개로 제한한다. 기기별 최대 5회 시도하며 Retry-After와 최소 60초부터의 지수 지연을 적용한다. 메시지 생성 후 15분이 지나거나 재시도 시각이 그 이후면 종료한다. 용량 초과는 경고 로그를 남기고 해당 새 요청을 생략하며 이미 저장된 채팅의 성공 응답을 바꾸지 않는다. FCM이 비활성화되어 있거나 상대방의 활성 구독이 없으면 새 요청을 보관하지 않으며, 이후 등록해도 과거 채팅 푸시를 재생하지 않는다. FCM 접수는 화면 표시·수신 완료를 뜻하지 않으며, 응답 유실 후 재시도는 중복 도착할 수 있으므로 프론트가 `messageId`로 합친다. 앱 재시작 시 미전송 작업은 유실될 수 있다. 로그인·구독을 다시 등록하고 메시지 이력으로 복구해야 한다. 프로세스 재시작을 넘는 푸시 보장이 필요하면 영속 세션·발송 원장을 별도 설계한다.
