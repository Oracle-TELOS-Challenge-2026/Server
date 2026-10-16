# BinTime API 계약 골격

출처: [Notion 통합 API 명세](https://false-resolution-beb.notion.site/34c9cc5a61234f3d92bb3c6bd58a0df4) 및 [공통 규약 v0.1](https://app.notion.com/p/3fbdaacb8cd4813ea423d8429b2447e2). 2026-10-16 조회 기준의 구현 제안 계약이다.

45개 HTTP 경로(REST·SSE)와 별도 WebSocket 계약을 정의한다. Controller와 DTO는 계약 골격이며 Service, Repository, Port/Adapter 구현, 인증, 비즈니스 검증, 예약, AI 호출 및 이벤트 전송 로직은 포함하지 않는다. Controller에 도달한 요청은 공통 실패 JSON과 501 상태를 반환한다. 성공 응답 스키마는 향후 구현할 계약이다.

개발 단계에서는 Spring Security 의존성을 제외하고 CSRF 헤더 바인딩을 선택으로 두었다. 명세의 인증·권한·CSRF 요구는 향후 구현할 계약이며 현재 런타임에서 적용되지 않는다.

## 이름 및 응답 규칙

- Controller는 리소스·호출 주체별로 구분한다. DTO는 `<동작><리소스>Request`, `<리소스>Response`, 목록은 `<리소스>ListResponse`를 사용한다.
- DTO는 도메인의 `dto` 패키지에 Java record로 정의한다. 공통 HTTP 봉투만 `common.dto`에 둔다. DTO를 도메인 엔티티로 공유하지 않는다.
- JSON 응답은 `BaseResponse<T>` 상속 기반의 `resultType/success.data/error/meta(timestamp,path)` 구조다. 기존 Notion의 최상위 data는 success.data로 이동한다. 목록의 items/page/counts 구조는 그 안에 유지한다. 204·OAuth redirect·SSE·WebSocket은 봉투 예외다. 자세한 계약은 [response.md](response.md)를 따른다.
- ID는 String, 오프셋 포함 시각은 OffsetDateTime, 날짜는 LocalDate, 금액은 정수 원이다. Web Push expirationTime은 Unix epoch 밀리초다.
- PATCH의 생략/명시적 null 구분, 필수 nullable 필드 존재 검사, 필드 간 조건 검증은 실제 구현 시 보완해야 한다. 현재 record와 Swagger 메타데이터만으로 런타임 검증이 구현된 것은 아니다.
- Swagger 첫 화면의 `all` 그룹은 전체 HTTP API 45개를 표시한다. `public`은 43개, `internal`은 2개를 표시한다. 내부 인증의 구체적인 방식은 미정이며 내부 계약은 외부 고객의 호출용이 아니다.
- Notion 예시에서 생략된 선택 필드(reason, openingHours 등)는 공통 규약을 기준으로 포함한다. ST-08은 OWNER 조회 시 customer/resourceId가 추가된다.

## 명세 간 차이

Notion v0.1은 슬롯 상태를 AVAILABLE/BOOKED/BLOCKED/EXPIRED로 정의하며, 기존 AGENTS.md의 HELD/CONFIRMED와 다르다. 알림도 조건 일치자 전원 대상으로 기존의 상위 5명 정책과 다르다. 이번 변경에서는 API 상태값을 Notion에 맞추고 두 정책의 실제 동작은 구현하지 않는다. 도메인 구현 전 정책 문서를 정합화해야 한다.

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

실시간 프레임은 [realtime.md](realtime.md)를 참고한다.
