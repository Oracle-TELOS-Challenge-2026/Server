# 채팅 예약 의사 분석과 프런트 인계

2026-10-17 요청. 프런트 소스는 수정하지 않았다. DB·기존 메시지/제안 응답·실제 예약 생성 규칙도 변경하지 않는다. 고객 메시지의 원문은 기존 `chatting_messages`에 유지하고 OpenAI 분석 결과만 별도 API로 제공한다.

## C-08

```http
POST /api/v1/chatting/rooms/{roomId}/messages/{messageId}/booking-suggestion
X-CSRF-Token: 현재 세션의 토큰
Cookie: bt_session=...
```

요청 본문과 Idempotency-Key는 필요 없다. 방의 고객 또는 현재 사장님만 **가장 최근 고객 TEXT 메시지**를 분석할 수 있다. 다른 방·사장님 메시지·제안 카드는 사용할 수 없다. 서버는 메시지 원문과 발송 시각을 DB에서 읽는다. 임의의 클라이언트 문장을 분석 대상으로 받지 않는다.

예: 2026-10-17에 저장한 `내일 17시에 머리자르고 싶어요`.

```json
{
  "resultType": "SUCCESS",
  "success": {
    "data": {
      "sourceMessageId": "msg_customer_001",
      "sourceContent": "내일 17시에 머리자르고 싶어요",
      "bookingRequested": true,
      "date": "2026-10-18",
      "time": "17:00",
      "serviceDescription": "커트"
    }
  },
  "error": null,
  "meta": {
    "timestamp": "2026-10-17T14:00:00",
    "path": "/api/v1/chatting/rooms/room_001/messages/msg_customer_001/booking-suggestion"
  }
}
```

- `ㅎㅇ`, 인사·감사·취소·거절만 하는 문장은 `bookingRequested=false`, 나머지 분석 필드는 null이다.
- 날짜와 시간은 각각 미언급·불명확하면 null이다. `내일 3시`처럼 오전·오후가 없는 시간은 추측하지 않는다. `17시`, `오후 5시`, `17:00`은 17:00이다.
- 오늘·내일·모레는 조회 시각이 아닌 **원문 발송 시각의 Asia/Seoul 날짜** 기준이다. 한국 자정 이후 다시 열어도 기준이 바뀌지 않는다.
- `serviceDescription`은 고객이 말한 시술·추가 옵션의 제안 초안이다. 실제 서비스 ID, 금액, 소요 시간을 만들어내지 않는다. 모델이 원문을 요약할 수 있으므로 원문도 함께 표시하고 사장님이 확인한다.
- 분석만으로 새 메시지·예약 제안·예약·푸시를 생성하지 않는다. 원문 메시지 저장은 기존 흐름으로 끝나며 AI 장애에 묶이지 않는다.
- 결과는 메시지 ID별 최대 256개 서버 메모리 캐시다. 최초 동시 요청은 중복 분석될 수 있고, 재시작·캐시 퇴출 후 재분석될 수 있다. 캐시 조회 전후에도 참여 권한과 최신 메시지를 검사한다.

## 동료가 수정할 프런트

1. `src/pages/ChatPage.tsx`에서 가장 최근 고객 TEXT의 저장 완료 ID를 기준으로 C-08을 호출한다. 메시지가 추가되었을 때와 방을 처음 열 때 호출하고, 5초 폴링마다 반복하지 않는다. 저장되지 않은 임시 메시지나 사장님 메시지는 분석하지 않는다.
2. `bookingRequested=true`이고 응답의 `sourceMessageId`가 여전히 가장 최근 고객 메시지일 때만 예약 안내·**예약 제안 작성** 버튼을 표시한다. 기존 `source가 있으면 항상 버튼 표시`하는 대체 분기를 삭제한다. `ㅎㅇ`에 버튼이 남아서는 안 된다. 분석 실패·로딩 중에는 예약 의사가 확인됐다는 안내를 먼저 표시하지 않는다.
3. 검토 버튼의 `reviewId`, 모달의 `source`, C-06에 보낼 `sourceMessageId`를 모두 C-08의 동일한 `sourceMessageId`로 연결한다. 원문 인용에는 `sourceContent`를 표시한다. 닫기·재열기 때 임의의 마지막 메시지로 바꾸지 않는다.
4. `src/components/owner/BookingProposalDialog.tsx`에 `date`, `time`, `serviceDescription`을 초깃값으로 전달한다. 현재 등록 시술 기본값만 넣는 로직 때문에 고객이 커트를 요청해도 탈색으로 보일 수 있다. 고객 요청 설명을 먼저 채우고 사장님이 실제 등록 시술·추가 옵션·시간·금액을 확인하게 한다. `serviceId`는 실제 매장 카탈로그에서 선택·검증하며 AI가 정한 ID를 사용하지 않는다.
5. `src/lib/chat-suggestion.ts`의 정규식 결과를 주 분석 결과로 사용하지 않는다. 현재 구현은 `17시`와 `자르고 싶어요`를 누락할 수 있다. AI 결과의 date/time은 각각 null 여부를 그대로 반영한다.
6. 새 고객 메시지가 오면 이전 분석·열린 모달을 무효화한다. 분석 HTTP 응답이 뒤늦게 도착해도 messageId가 달라졌다면 무시한다. 이전 요청의 `ㅎㅇ`를 새 원문으로 바꾸어 전송하지 않는다. C-06은 여전히 최신 고객 TEXT만 허용한다.

기존 흐름은 유지한다: 고객 원문 저장 → 의사 분석·사장님 검토 → C-06 제안 생성 → 고객이 C-07로 확정. 실제 예약 시간 충돌·권한·원자적 예약 생성은 기존 서버 검증을 그대로 따른다.

## 오류

| 상태 | 코드 | 프런트 처리 |
| --- | --- | --- |
| 401/403 | UNAUTHORIZED / CSRF_INVALID | 세션·CSRF 갱신 |
| 404 | ROOM_NOT_FOUND / SOURCE_MESSAGE_NOT_FOUND | 잘못된 방·원문, 안내 제거 |
| 409 | PROPOSAL_SOURCE_OUTDATED / ROOM_READ_ONLY | 기존 분석·모달 무효화 후 최근 메시지 조회 |
| 503 | OPENAI_NOT_CONFIGURED / CHAT_BOOKING_ANALYSIS_UNAVAILABLE | 원문 채팅은 유지. 분석 실패·재시도 안내. 예약 의사를 확정 표시하지 않음 |

## 사장님 푸시 확인

원문 TEXT의 커밋이 기존 채팅 FCM을 발생시킨다. C-08 조회로 같은 푸시를 추가 전송하지 않는다. 수신자의 현재 로그인 세션·활성 알림 구독이 필요하며, 이미 읽은 메시지는 발송하지 않는 기존 정책을 유지한다.

2026-10-17 한국 시간의 운영 DB 읽기 전용 점검에서 FCM 활성화와 최신 푸시 이력 코드 적용을 확인했다. 사용자가 재현한 **09:49:51~09:49:57 고객 메시지 10건 모두** 사장님 기기 대상으로 `QUEUED → RUNNING → ACCEPTED` 이력이 남았다. 각 메시지의 첫 발송 시도가 FCM에 접수됐으며 실패·제외 이력은 없었다. 이 전송 건의 서버 발송 누락은 확인되지 않았다. 다만 FCM 접수 성공이 브라우저 수신·운영체제 알림센터 표시까지 보장하지는 않는다.

고객·사장님을 서로 다른 계정으로 로그인하고 사장님 알림을 켠 뒤, 사장님을 해당 방 밖/다른 탭으로 이동시켜 **새 고객 메시지**를 보낸다. `notification_push_history`의 해당 메시지 `QUEUED → RUNNING → ACCEPTED` 또는 `SKIPPED/FAILED.error_code`로 서버 결과를 확인한다. ACCEPTED인데 표시되지 않는 경우 프런트 담당자는 해당 사장님 브라우저의 FCM 수신·서비스 워커(`public/firebase-messaging-sw.js`), 사이트 알림 권한과 운영체제 알림 설정을 확인한다. 앱 앞에서 보이는 토스트와 백그라운드 알림센터 표시는 별도 경로다.

OpenAI 요청은 기존 `OPENAI_API_KEY`, `search.ai.model`(기본 gpt-4.1-mini), `search.ai.base-url`을 사용한다. 새 키나 DB 마이그레이션은 필요 없다. [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)로 출력 구조를 제한하며, 사장님이 최종 내용을 확인해야 한다.

## 검증 및 적용 상태

단위·권한/CSRF·Swagger 계약·전체 빌드를 통과했다(412개 중 406개 통과, 선택 실행 6개 제외). 실제 OpenAI로 `내일 17시 커트`, `ㅎㅇ`, 예약 취소, 모호한 `3시`, 날짜 정정, 날짜·시간 없는 시술 요청의 합성 문장 6개도 별도 실행해 검증했다.

```bash
./gradlew format build --no-daemon
RUN_OPENAI_INTEGRATION=1 ./gradlew test --tests '*ChatBookingAiLiveTests' --no-daemon
```

두 번째 명령은 실제 OpenAI를 호출한다. 키 값은 출력하지 않으며 기본 빌드에서는 실행하지 않는다. 현재 프런트·운영 앱 배포·새 DB 변경은 수행하지 않았다. 백엔드 배포 후 동료가 C-08과 화면을 연결해야 버튼과 검토 화면에 반영된다. 실제 사용자 간 알림 수신 확인은 별도다.
