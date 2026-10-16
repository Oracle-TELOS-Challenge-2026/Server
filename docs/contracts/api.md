# BinTime API 계약

출처: [Notion 통합 API 명세](https://false-resolution-beb.notion.site/34c9cc5a61234f3d92bb3c6bd58a0df4) 및 [공통 규약 v0.1](https://app.notion.com/p/3fbdaacb8cd4813ea423d8429b2447e2). 2026-10-16 조회 기준의 구현 제안 계약이다. C-06·C-07 예약 제안은 2026-10-17 사용자 요청으로 추가한 로컬 확장이며 Notion에서 가져온 항목이 아니다. 원본 [notion-api.json](../../src/test/resources/contracts/notion-api.json)은 유지하고 [chat-booking-proposals.json](../../src/test/resources/contracts/chat-booking-proposals.json)에 추가 요청·응답 예시를 보관한다.

50개 HTTP operation(REST·SSE)와 별도 WebSocket 계약을 정의한다. U-01~09는 카카오 로그인·세션·CSRF 및 [프로필·위치·선호도](../user-profile.md)로 구현했다. **search S-01~08 구현 및 계약 변경은 [search.md](../search.md)를 우선한다. S-03/S-06은 notificationConsent=true가 필수이며 501 골격 대상에서 제외한다.** store ST-01~20은 [store 구현 계약](../store.md)에 따라 영속 동작을 제공한다. notification N-01~06은 저장·읽음·SSE·FCM으로 구현했다. chatting C-01~07과 텍스트·예약 제안 WebSocket 이벤트는 [채팅 연동 안내](../chatting.md)에 따라 구현했다.

Spring Security가 `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/notifications/**`의 세션 인증·CSRF를 적용한다. store의 공개 조회 5개를 제외한 15개 API에는 세션 쿠키·CSRF 및 선택적 Bearer JWT 인증을 적용한다. [store 인증 계약](../store-auth.md)에 적용 범위와 설정을 정의한다. search는 매장 검색을 공개하고 대기·분석 API는 현재 CUSTOMER 또는 OWNER 인증과 쿠키 변경 요청 CSRF를 검증한다. 내부 검색은 별도 내부 토큰을 유지한다([search 인증](../search-auth.md)). `SearchUserAccessAdapter`가 공통 세션/JWT 인증 경계와 알림 구독 조회를 연결한다. chatting은 별도 필터 체인으로 카카오 세션 쿠키·변경 요청 CSRF를 검증하고 방의 고객·현재 매장 소유자만 허용한다. WebSocket은 세션·Origin·첫 프레임 CSRF를 검증한다. `api-docs` 프로필은 문서 열람용으로 Spring Security 필터와 DB 의존성을 제외하며, search 자체의 요청자 검증은 유지한다. 자세한 로그인 설정은 [카카오 로그인](../kakao-login.md)을 따른다.

## 이름 및 응답 규칙

- Controller는 리소스·호출 주체별로 구분한다. DTO는 `<동작><리소스>Request`, `<리소스>Response`, 목록은 `<리소스>ListResponse`를 사용한다.
- DTO는 도메인의 `dto` 패키지에 Java record로 정의한다. 공통 HTTP 봉투만 `common.dto`에 둔다. DTO를 도메인 엔티티로 공유하지 않는다.
- JSON 응답은 `BaseResponse<T>` 상속 기반의 `resultType/success.data/error/meta(timestamp,path)` 구조다. 기존 Notion의 최상위 data는 success.data로 이동한다. 목록의 items/page/counts 구조는 그 안에 유지한다. 204·OAuth redirect·SSE·WebSocket은 봉투 예외다. 자세한 계약은 [response.md](response.md)를 따른다.
- ID는 String, 오프셋 포함 시각은 OffsetDateTime, 날짜는 LocalDate, 금액은 정수 원이다. Web Push expirationTime은 Unix epoch 밀리초다.
- 사용자 API는 필수값·범위와 U-08의 필수 nullable `maxPrice` 키 존재 여부를 검증한다. 나머지 골격의 PATCH 생략/명시적 null 구분 및 필드 간 검증은 실제 구현 시 보완한다.
- Swagger 첫 화면의 `all` 그룹은 전체 HTTP API 50개를 표시한다. `public`은 48개, `internal`은 2개를 표시한다. chatting의 HTTP API는 7개이며 WebSocket은 이 수에 포함하지 않는다. 내부 계약은 외부 고객의 호출용이 아니며 search 내부 인증 설정은 [search.md](../search.md)를 따른다.
- Notion 예시에서 생략된 선택 필드(reason, openingHours 등)는 공통 규약을 기준으로 포함한다. ST-08은 OWNER 조회 시 customer/resourceId가 추가된다.

## 명세 간 차이

- S-01 기본 거리순 지도 조회는 날짜 미지정·`vacancyOnly=false`일 때 슬롯이 없는 등록 매장도 즉시 반환한다. DB의 공개 카탈로그에서 읽어 비동기 색인에 의존하지 않는다. `bestSlot`은 필수 키이지만 null을 허용하며 프론트는 이 경우에도 마커를 표시한다. 명시적인 슬롯 조건과 가격순·추천순 검색은 기존 동작을 유지한다. 상세 조건과 커서 의미는 [search.md](../search.md#기본-지도-매장-조회)를 따른다.

Notion v0.1은 슬롯 상태를 AVAILABLE/BOOKED/BLOCKED/EXPIRED로 정의하며, 기존 AGENTS.md의 HELD/CONFIRMED와 다르다. 알림 정책은 search 구현에서 **필수 조건 일치 고객 전원**으로 확정했다. 벡터 top-k/유사도 임계값으로 고객을 제외하지 않는다. 시간 조건은 시술 시작부터 종료까지 전체 포함이다. 슬롯 선점/예약 상태는 store 책임으로 유지한다.

## API 이름 매핑

| ID | 도메인 | 경로 | Controller 메서드 | Request DTO | Response data DTO |
| --- | --- | --- | --- | --- | --- |
| U-01 | user | GET `/api/v1/auth/oauth/kakao/start` | AuthController.startKakaoLogin | — | — |
| U-02 | user | GET `/api/v1/auth/oauth/kakao/callback` | AuthController.handleKakaoCallback | — | — |
| U-03 | user | POST `/api/v1/auth/logout` | AuthController.logout | — | — |
| U-09 | user | GET `/api/v1/auth/csrf` | AuthController.getCsrfToken | — | CsrfTokenResponse |
| U-04 | user | GET `/api/v1/users/me` | UserController.getMyProfile | — | UserProfileResponse |
| U-05 | user | PATCH `/api/v1/users/me` | UserController.updateMyProfile | UpdateProfileRequest | UserProfileResponse |
| U-06 | user | PUT `/api/v1/users/me/location` | UserController.saveMyLocation | SaveLocationRequest | UserLocationResponse |
| U-07 | user | DELETE `/api/v1/users/me/location` | UserController.deleteMyLocation | — | — |
| U-08 | user | PUT `/api/v1/users/me/preferences` | UserController.saveMyPreferences | SavePreferencesRequest | UserPreferencesResponse |
| ST-01 | store | GET `/api/v1/stores/categories` | StoreController.getCategories | — | CategoryListResponse |
| ST-02 | store | GET `/api/v1/stores/{storeId}` | StoreController.getStore | — | StoreDetailResponse |
| ST-03 | store | GET `/api/v1/stores/{storeId}/services` | StoreController.getServices | — | StoreServiceListResponse |
| ST-04 | store | GET `/api/v1/stores/{storeId}/slots` | StoreController.getAvailableSlots | — | ReservationSlotListResponse |
| ST-05 | store | GET `/api/v1/stores/{storeId}/slots/{slotId}` | StoreController.getSlot | — | ReservationSlotResponse |
| ST-06 | store | POST `/api/v1/reservations` | ReservationController.createReservation | CreateReservationRequest | ReservationResponse |
| ST-07 | store | GET `/api/v1/reservations/me` | ReservationController.getMyReservations | — | ReservationListResponse |
| ST-08 | store | GET `/api/v1/reservations/{reservationId}` | ReservationController.getReservation | — | ReservationResponse |
| ST-09 | store | POST `/api/v1/reservations/{reservationId}/cancel` | ReservationController.cancelReservation | CancelReservationRequest | CancelReservationResponse |
| ST-10 | store | PATCH `/api/v1/owner/stores/{storeId}` | OwnerStoreController.updateStore | UpdateStoreRequest | StoreDetailResponse |
| ST-11 | store | GET `/api/v1/owner/stores/{storeId}/timetable` | OwnerStoreController.getTimetable | — | TimetableResponse |
| ST-12 | store | GET `/api/v1/owner/stores/{storeId}/reservations/{reservationId}` | OwnerReservationController.getReservation | — | OwnerReservationResponse |
| ST-13 | store | POST `/api/v1/owner/stores/{storeId}/reservations/{reservationId}/status` | OwnerReservationController.updateReservationStatus | UpdateReservationStatusRequest | UpdateReservationStatusResponse |
| ST-14 | store | GET `/api/v1/owner/stores/{storeId}/vacancies` | OwnerVacancyController.getVacancies | — | VacancyListResponse |
| ST-15 | store | POST `/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish` | OwnerVacancyController.publishVacancy | PublishVacancyRequest | PublishVacancyResponse |
| ST-16 | store | POST `/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/withdraw` | OwnerVacancyController.withdrawVacancy | WithdrawVacancyRequest | WithdrawVacancyResponse |
| ST-17 | store | PATCH `/api/v1/owner/stores/{storeId}/services/{serviceId}` | OwnerStoreController.updateService | UpdateStoreServiceRequest | StoreServiceResponse |
| S-01 | search | GET `/api/v1/search/stores` | SearchController.searchStores | — | StoreSearchResponse |
| S-02 | search | POST `/api/v1/search/intents/analyze` | SearchController.analyzeIntent | AnalyzeIntentRequest | AnalyzeIntentResponse |
| S-03 | search | POST `/api/v1/search/waitlists` | WaitlistController.createWaitlist | CreateWaitlistRequest | WaitlistResponse |
| S-04 | search | GET `/api/v1/search/waitlists/me` | WaitlistController.getMyWaitlists | — | WaitlistListResponse |
| S-05 | search | GET `/api/v1/search/waitlists/{waitlistId}` | WaitlistController.getWaitlist | — | WaitlistResponse |
| S-06 | search | PATCH `/api/v1/search/waitlists/{waitlistId}` | WaitlistController.updateWaitlist | UpdateWaitlistRequest | WaitlistResponse |
| S-07 | search | POST `/api/v1/search/waitlists/{waitlistId}/cancel` | WaitlistController.cancelWaitlist | CancelWaitlistRequest | WaitlistResponse |
| S-08 | search | POST `/api/v1/internal/search/match-jobs` | InternalSearchController.createMatchJob | CreateMatchJobRequest | MatchJobResponse |
| N-01 | notification | GET `/api/v1/notifications` | NotificationController.getNotifications | — | NotificationListResponse |
| N-02 | notification | PATCH `/api/v1/notifications/{notificationId}/read` | NotificationController.markNotificationRead | MarkNotificationReadRequest | NotificationResponse |
| N-03 | notification | GET `/api/v1/notifications/stream` | NotificationController.streamNotifications | — | SSE |
| N-04 | notification | POST `/api/v1/notifications/push-subscriptions` | PushSubscriptionController.registerPushSubscription | RegisterPushSubscriptionRequest | PushSubscriptionResponse |
| N-05 | notification | DELETE `/api/v1/notifications/push-subscriptions/{subscriptionId}` | PushSubscriptionController.deletePushSubscription | — | — |
| N-06 | notification | POST `/api/v1/internal/notifications/dispatch` | InternalNotificationController.dispatchNotification | DispatchNotificationRequest | DispatchNotificationResponse |
| C-01 | chatting | POST `/api/v1/chatting/rooms` | ChatRoomController.createChatRoom | CreateChatRoomRequest | ChatRoomResponse |
| C-02 | chatting | GET `/api/v1/chatting/rooms` | ChatRoomController.getChatRooms | — | ChatRoomListResponse |
| C-03 | chatting | GET `/api/v1/chatting/rooms/{roomId}/messages` | ChatMessageController.getMessages | — | ChatMessageListResponse |
| C-04 | chatting | POST `/api/v1/chatting/rooms/{roomId}/messages` | ChatMessageController.sendMessage | SendChatMessageRequest | ChatMessageResponse |
| C-05 | chatting | PUT `/api/v1/chatting/rooms/{roomId}/read` | ChatRoomController.updateReadPosition | UpdateChatReadRequest | ChatReadResponse |
| C-06 | chatting | POST `/api/v1/chatting/rooms/{roomId}/booking-proposals` | ChatBookingProposalController.createBookingProposal | CreateBookingProposalRequest | ChatMessageResponse |
| C-07 | chatting | POST `/api/v1/chatting/rooms/{roomId}/booking-proposals/{proposalId}/confirm` | ChatBookingProposalController.confirmBookingProposal | ConfirmBookingProposalRequest | ChatMessageResponse |

실시간 프레임은 [realtime.md](realtime.md)를 참고한다.

### 채팅 읽음 위치 복구

`ChatRoomResponse`는 기존 본인 `lastReadSequence`와 함께 상대방 `peerReadSequence`를 필수 숫자로 반환한다. 상대방은 해당 방의 고객 또는 현재 매장 사장님이며 읽음 기록이 없으면 0이다. C-02 목록에서 재로그인·새로고침 후 복원하고, WebSocket 구독·재구독 때 양쪽 읽음 위치를 기존 `room.read.updated`로 동기화한다. 최초 0도 전달하며 REST·이벤트는 사용자별 최댓값으로 병합한다. 기존 DB 구조와 C-05 요청·응답은 유지한다. 원본 Notion 스냅샷에는 없는 확장이며 세부 규칙은 [채팅 연동 안내](../chatting.md)를 따른다.

### 로컬 확장: 채팅 예약 제안

C-06은 매장의 현재 사장님이 고객의 가장 최근 TEXT를 원본으로 실제 시술·추가 옵션 설명, 시작 시각, 소요 시간, 최종 금액을 저장한다. 등록 시술의 기본값과 별도로 저장하며 전송만으로 예약이나 시간 선점을 만들지 않는다. 성공은 카드 메시지 `201`이다. 신규 텍스트와 제안 생성은 사용자별 합산 초당 5건 제한을 적용하며 초과하면 `429 RATE_LIMITED`다.

C-07은 해당 방 고객이 `expectedVersion`만 보내 저장된 제안으로 예약을 확정한다. 확정 시 기존 영업시간·자원·고객 예약·대기 조건을 검증하고 실제 예약과 제안 상태를 한 트랜잭션으로 저장한다. 성공은 갱신된 카드 메시지 `200`이다. 두 요청은 `bt_session`, `X-CSRF-Token`, `Idempotency-Key`가 필수이며 같은 요청은 최초 성공 응답·상태를 24시간 재생한다. 클라이언트가 보낸 사용자 ID·확정 금액·시술 시간으로 저장 내용을 덮어쓰지 않는다.

`ChatMessageResponse.type`에 `BOOKING_PROPOSAL`과 nullable `bookingProposal`이 추가된다. 카드 상태는 `PENDING`, `CONFIRMED`, `SUPERSEDED`, `REVIEW_REQUIRED`이며 버전으로 병합한다. **CONFIRMED는 해당 제안으로 예약이 확정되었다는 기록이다.** 이후 예약 취소·완료 등 현재 예약 상태는 `reservationId`로 기존 예약 API에서 조회하며 카드의 상태를 현재 예약 상태로 해석하지 않는다. 새 고객 TEXT는 미확정 제안을 재검토 상태로, 새 제안은 이전 미확정 제안을 대체 상태로 만든다. 권한·버전·무효화·충돌 오류와 재접속 동기화는 [예약 제안 명세](../chatting.md#예약-제안--c-06c-07)를 따른다.

## 추가 store 등록 API

| 구분 | 도메인 | 메서드·경로 | Controller | 요청 DTO | 응답 DTO |
| --- | --- | --- | --- | --- | --- |
| ST-18 | store | POST `/api/v1/owner/stores` | OwnerStoreController.createStore | CreateStoreRequest | StoreDetailResponse |
| ST-19 | store | POST `/api/v1/owner/stores/{storeId}/services` | OwnerStoreController.createService | CreateStoreServiceRequest | StoreServiceResponse |
| ST-20 | store | POST `/api/v1/owner/stores/{storeId}/slots` | OwnerStoreController.createSlot | CreateSlotRequest | ReservationSlotResponse |

store MVP는 하나의 DB 트랜잭션에서 AVAILABLE → BOOKED로 확정하며 별도 HELD API를 도입하지 않는다. 예약 상태는 CONFIRMED와 후속 최종 상태로 구분한다. 한 매장의 동시 예약 자원은 1개다. store의 입력·권한·멱등성·마이그레이션·영속 작업·운영 인증 조건은 [store.md](../store.md)를 따른다.

### 사용자 선호 임베딩 연동

사용자 벡터 입력은 `user_accounts.preferences_json`의 자유로운 JSON 문자열 전체다. 내부 `UserPreferenceService`가 읽기/저장과 변경 이벤트를 제공하고 search가 OpenAI 호출/비동기 갱신/Oracle 벡터 저장을 맡는다. U-08은 categories·keywords·maxPrice·text와 사용자 버전을 저장하고 같은 트랜잭션에서 변경 이벤트를 발행해 VECTOR 작업을 등록한다. 200 응답은 선호 저장·작업 접수 성공이며 벡터 생성 완료는 아니다. 동일 입력은 기존 벡터를 재사용하고 OpenAI 일시 장애는 worker에서 재시도한다. 다른 사용자의 선호 원문이나 벡터를 공개하는 새 HTTP API는 추가하지 않았다. 세부 오류와 마이그레이션/운영 명령은 [search 안내](../search.md)를 따른다.
