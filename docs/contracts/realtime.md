# 실시간 전송 계약

출처: [Notion 공통 규약 v0.1](https://app.notion.com/p/3fbdaacb8cd4813ea423d8429b2447e2). DTO와 문서만 정의하며 실제 SSE 연결, WebSocket 업그레이드 및 메시지 처리는 미구현이다.

## 알림 SSE — N-03

`GET /api/v1/notifications/stream`, `Accept: text/event-stream`, 인증은 `bt_session` 쿠키다. `Last-Event-ID`와 `afterEventId`가 함께 있으면 헤더가 우선한다. 사용자별 이벤트 ID는 알림 ID와 별개다.

```text
retry: 3000

id: evt_user_000013
event: notification.created
data: {"notification":{"id":"noti_001","type":"VACANCY_MATCHED"}}

: heartbeat
```

위 notification은 축약 예시이며 전체 필드는 Swagger의 `NotificationResponse`를 따른다. JSON data는 `NotificationStreamPayload`로 표현하며 HTTP 응답의 resultType/success/error/meta 봉투를 씌우지 않는다. 스트림 시작 전의 HTTP 오류 응답은 공통 실패 봉투를 사용한다.

| event | data DTO | 의미 |
| --- | --- | --- |
| `notification.created` | `NotificationStreamPayload.Created` | 저장된 알림 전달 |
| `notification.read` | `NotificationStreamPayload.Read` | 다른 탭의 읽음 상태 반영 |
| `session.expired` | `NotificationStreamPayload.SessionExpired` | 세션 만료 후 연결 종료 |

최근 24시간 재생, 15초 heartbeat, 최초 연결에서는 현재 이후 이벤트를 전달한다. 너무 오래된 커서는 스트림 시작 전 410 `STREAM_CURSOR_EXPIRED`다. 연결 후 알림 목록을 조회하고 notification.id로 병합한다. 재생과 실시간 전환 사이에 누락이 없어야 한다. 응답은 `Cache-Control: no-cache` 및 프록시 버퍼링 비활성화가 필요하다.

## 채팅 WebSocket — C-WS

- 주소: `wss://{api-host}/api/v1/chatting/ws`
- 서브프로토콜: `bintime.chat.v1`
- 세션 쿠키 및 Origin 검증 후 업그레이드. 장기 토큰을 URL에 넣지 않는다.
- 프레임: `ChatWebSocketFrame(type, requestId, payload)`. 클라이언트 requestId는 최대 64자이며 응답 연결용이다. 서버 이벤트에서는 생략할 수 있다.
- Swagger는 프레임 스키마를 보여준다. WebSocket을 REST GET API로 가장하거나 Try it out으로 연결하지 않는다.
- type에 따른 payload 선택·역직렬화와 권한 검증은 실제 WebSocket 구현에서 처리해야 한다. 현재 sealed payload DTO는 전송 스키마만 표현한다.

| type | payload (`ChatWebSocketFrame` 내부 record) |
| --- | --- |
| `connection.authenticate` | `Authenticate(csrfToken)` |
| `connection.ready` | `Ready(userId, heartbeatSeconds)` |
| `room.subscribe` | `Subscribe(roomId, afterSequence)` |
| `room.subscribed` | `Subscribed(roomId, replayThroughSequence)` |
| `room.resync_required` | `ResyncRequired(roomId, latestSequence)` |
| `room.unsubscribe` / `room.unsubscribed` | `Room(roomId)` |
| `message.send` | `SendMessage(roomId, clientMessageId, content)` |
| `message.ack` / `message.created` | `Message(message)` — REST와 동일한 ChatMessageResponse |
| `room.read` | `Read(roomId, lastReadSequence)` |
| `room.read.ack` | `ReadAck(roomId, lastReadSequence, readAt)` |
| `room.read.updated` | `ReadUpdated(roomId, userId, lastReadSequence, readAt)` |
| `ping` | `Ping()` |
| `pong` | `Pong(serverTime)` |
| `error` | `Error(code, message, retryable, retryAfterSeconds?)` |

연결 후 5초 이내 CSRF 인증을 완료한다. ACK는 영속 저장만 의미한다. REST와 WS에서 동일한 clientMessageId를 유지해 재전송한다. 구독 시 afterSequence 초과부터 replayThroughSequence까지 재생 후 실시간 메시지를 잇는다. 누락 1000건 초과는 REST 이력 조회 후 재구독한다.

메시지는 1~2000자 일반 텍스트, 프레임 최대 16KiB, 전송 제한은 사용자당 초당 5개, 동시 구독은 최대 20개를 제안한다. ping은 25초, 60초 무응답이면 재연결한다. 4401은 세션 만료, 4403은 인증·정책 위반, 4429는 반복적인 과다 요청이다. 재연결은 1→2→4→8→최대 30초 지터 방식이며 4401/4403은 무한 재시도하지 않는다.
