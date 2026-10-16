# 실시간 전송 계약

출처: [Notion 공통 규약 v0.1](https://app.notion.com/p/3fbdaacb8cd4813ea423d8429b2447e2). 알림 SSE·FCM 및 텍스트 채팅 WebSocket을 구현했다.

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

현재 단일 앱은 영속 이벤트 로그를 1초마다 최대 100건씩 읽는다. 사용자별 DB 잠금으로 이벤트 커밋 순서를 정하고 같은 로그에서 재생과 새 이벤트를 읽어 전환 누락을 방지한다. 재생 시간 경계는 연결 시 고정한다. 타인의 커서·존재하지 않는 커서는 400이며, 유효한 본인 커서 중 24시간이 지난 커서는 410이다. 세션 유휴 만료·로그아웃·계정 변경 시 `session.expired`를 보내고 종료한다. SSE heartbeat는 로그인 세션의 유휴 시간을 연장하지 않는다. 이벤트 로그의 자동 삭제는 아직 수행하지 않는다.

## FCM 알림 등록·발송 — N-04~06

store 발행 → search 매칭 → 알림 접수·발송의 연결과 통합 테스트는 [store 알림 통합](../store-notification-integration.md)을 참고한다.

- N-04 `POST /api/v1/notifications/push-subscriptions`는 `{"installationId":"<FID>"}`만 받는다. 문자열 1~128자이며 공백·제어문자는 허용하지 않는다. 이전 Web Push endpoint/keys 계약은 대체됐다.
- 신규 연결은 201, 같은 사용자·FID 재등록은 같은 구독 ID와 최초 createdAt을 유지하며 200이다. `expiresAt:null`은 Firebase 만료 시각을 알 수 없다는 의미다. 다른 계정의 활성 FID는 409 `SUBSCRIPTION_IN_USE`다.
- 한 세션의 새 FID 등록은 이전 연결을 원자적으로 교체한다. 같은 FID를 공유하는 다른 유효 세션과 다른 브라우저 FID는 유지한다.
- N-05는 FID 대신 구독 ID로 삭제한다. 타인의 활성 구독은 404, 본인 구독·이미 비활성화된 구독·없는 ID는 204다. 본인의 해당 FID에 연결한 모든 세션을 해제한다.
- 로그아웃은 현재 세션만 해제한다. 마지막 세션의 만료·계정 변경·서버 재시작 후에는 발송 대상에서 제외한다. 다시 로그인한 브라우저는 N-04를 호출해야 한다. 단일 프로세스 메모리 세션 구성에 맞춘 정책이며 다중 앱 서버를 지원하지 않는다.
- 등록은 브라우저 알림 수신 동의로 사용하고, 실제 매칭 발송은 별도로 명시적 동의가 있는 ACTIVE 대기의 현재 버전·조건과 슬롯 공개 버전을 검증한다. 사용자별 별도 전역 알림 설정은 만들지 않았다. N-04 재등록은 이전에 건너뛴 푸시를 재생하거나 대기 버전을 바꾸지 않는다. 기존 슬롯 탐색은 대기 동의를 등록·수정하는 S-03/S-06 흐름에서 수행한다.
- 접수 시 검증은 조회 시점의 스냅샷이며 이미 저장된 알림은 취소 후에도 이력으로 남는다. 매 발송 직전 조건·슬롯·세션을 다시 검사해 그 전에 완료된 취소·만료를 반영한다. 이미 시작한 외부 HTTP 요청과 취소는 원자적으로 묶지 않는다.
- N-06의 HTTP 호출은 `X-Internal-Token`과 `notification.internal-token`이 일치해야 한다. 빈 서버 설정은 거절한다. 검색 모듈은 `SearchNotificationGateway`를 직접 호출한다. 외부 고객의 임의 발송 API는 없다.
- 사용자+슬롯당 알림을 한 번 저장한다. 무효 후보는 200 SKIPPED, 접수·중복 접수는 202 QUEUED다. FCM 제출 직전 현재 대기·슬롯·세션 구독을 다시 확인한다. 목록의 availability도 현재 슬롯을 기준으로 계산하며 외부 확인 실패 시 UNKNOWN이다.
- 기기별 작업을 DB에 보존하고 5초 간격으로 처리한다. 사용자당 발송 간격 기본 1초(`notification.delivery.minimum-interval-seconds`)는 지연 처리하며 알림을 버리지 않는다. FCM 429/5xx·통신 실패는 Retry-After 및 지수 지연을 적용해 최대 5회 시도한다. UNREGISTERED만 구독을 비활성화하며 일반 INVALID_ARGUMENT는 비활성화하지 않는다.
- 발송 성공은 FCM 접수이며 기기 수신·읽음·예약 확정을 뜻하지 않는다. 통신 응답 유실·프로세스 중단 후 재시도는 같은 notificationId를 사용하지만 FCM 전송 자체의 정확히 한 번 처리를 보장하지 않는다. 클라이언트도 notificationId로 중복을 제거해야 한다.
- 매칭 FCM payload는 `message.fid`, notification title/body, 문자열 data.url=`/customer/waitlist`, data.notificationId를 포함한다. HTTPS 프론트의 동일 origin 링크만 webpush.fcm_options.link에 넣으며 로컬 HTTP에서는 해당 링크 필드를 생략한다. 채팅은 같은 FID 등록·전송을 사용하고 `CHAT_MESSAGE` 데이터와 `/chat?roomId=...` 링크를 보낸다([채팅 푸시](../chatting.md#채팅-fcm-푸시)).

서버 인증·마이그레이션은 [환경 문서](../../infra/README.md#fcm-알림-설정)를 따른다. 등록·해제에는 기존 세션 쿠키와 X-CSRF-Token을 사용한다. FID는 인증 자격 증명이 아니며 서비스 계정 JSON을 브라우저에 전달하지 않는다. 공식 규격: [Firebase 웹 FID 등록](https://firebase.google.com/docs/cloud-messaging/web/get-started#access_the_firebase_installation_id), [HTTP v1 FID 예제](https://github.com/firebase/quickstart-js/blob/master/messaging/README.md#http), [FCM 오류 코드](https://firebase.google.com/docs/cloud-messaging/error-codes).

## 채팅 WebSocket — C-WS

저장 구조와 더미데이터 입력 규칙은 [채팅 DB 계약 v1](../chatting-db.md)을 따른다. 로컬·OCI에 같은 `004-chatting.sql`을 적용한다. REST와 WebSocket은 같은 저장·중복 제거 경로를 사용한다. 실행 예시는 [채팅 연동 안내](../chatting.md)를 따른다.

- 주소: `wss://{api-host}/api/v1/chatting/ws`
- 서브프로토콜: `bintime.chat.v1`
- 세션 쿠키 및 Origin 검증 후 업그레이드. 장기 토큰을 URL에 넣지 않는다.
- 프레임: `ChatWebSocketFrame(type, requestId, payload)`. 클라이언트 requestId는 최대 64자이며 응답 연결용이다. 서버 이벤트에서는 생략할 수 있다.
- Swagger는 프레임 스키마를 보여준다. WebSocket을 REST GET API로 가장하거나 Try it out으로 연결하지 않는다.
- 핸들러가 type별 payload를 검증한다. 알 수 없는 type·잘못된 JSON·필수 필드 누락은 `error`이며 인증 전 오류는 연결도 종료한다.

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

메시지는 1~2000자 일반 텍스트, 프레임 최대 16KiB, 전송 제한은 사용자당 초당 5개, 동시 구독은 연결당 최대 20개다. 초당 5개 제한은 REST·WS를 합친 신규 저장에 적용하며 같은 메시지의 재전송은 제한 횟수를 소모하지 않는다. ping은 25초, 60초 무응답이면 재연결한다. 4401은 앱에서 감지한 세션 만료, 4403은 인증·정책 위반, 4429는 반복적인 과다 요청이다. HTTP 세션 무효화 시 Tomcat이 먼저 소켓을 닫으면 표준 정책 위반 코드 1008이 올 수 있다. 재연결은 1→2→4→8→최대 30초 지터 방식이며 4401/4403/1008은 무한 재시도하지 않고 로그인·CSRF 상태부터 확인한다.


구독 경계와 실시간 전달은 DB의 방별 sequence 커서를 공유한다. 커밋 후 콜백 순서가 바뀌어도 DB에서 오름차순으로 읽고, 읽음 이벤트도 같은 연결에서 이전 값으로 돌아가지 않는다. 구독하지 않은 방에도 참여자라면 `message.send`·`room.read`를 보낼 수 있지만 해당 방의 실시간 이벤트는 받지 않는다. 수신 UI는 `message.id` 또는 방별 `sequence`로 중복을 제거하고 읽음 위치는 최댓값으로 병합한다. 자신의 메시지도 `message.ack`와 `message.created` 양쪽으로 올 수 있으며 순서는 보장하지 않는다.

브라우저 WebSocket 프로토콜의 Ping에 자동으로 Pong을 응답한다. JSON `ping`에도 JSON `pong`을 반환한다. 세션 만료·로그아웃·계정 변경 시 연결을 닫으며 heartbeat는 HTTP 세션 유휴 시간을 연장하지 않는다. Origin은 `FRONTEND_URL`의 origin 한 개만 허용한다. 로컬 개발 시 프론트·API 호스트를 모두 `localhost`로 맞추고 쿠키로 로그인해야 한다. Bearer·URL 토큰·개발용 사용자 헤더는 채팅 인증에 사용하지 않는다.

현재 전송·전송 제한은 단일 앱 프로세스 기준이다. DB는 메시지를 보존하지만 앱 재시작 때 소켓은 닫히므로 저장한 `afterSequence`로 재구독한다. 여러 앱 인스턴스로 확장하려면 프로세스 간 이벤트 전달을 추가해야 한다. 이미지·파일 첨부는 포함하지 않는다. 채팅 FCM은 커밋 후 상대방의 활성 구독에 비동기로 발송하며, 읽음·구독·참여자 상태를 발송 직전에 다시 확인한다. 채팅 푸시의 제한된 재시도 대기열은 메모리에 있으며 앱 재시작 때 미전송 작업이 유실될 수 있다. Oracle 스키마와 매칭 알림 원장은 변경하지 않는다.
