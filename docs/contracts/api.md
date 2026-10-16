# BinTime API 계약 골격

출처: [Notion 통합 API 명세](https://false-resolution-beb.notion.site/34c9cc5a61234f3d92bb3c6bd58a0df4) 및 [공통 규약 v0.1](https://app.notion.com/p/3fbdaacb8cd4813ea423d8429b2447e2). 2026-10-16 조회 기준의 구현 제안 계약이다.

48개 HTTP operation(REST·SSE)와 별도 WebSocket 계약을 정의한다. U-01~09는 카카오 로그인·세션·CSRF 및 [프로필·위치·선호도](../user-profile.md)로 구현했다. **search S-01~08 구현 및 계약 변경은 [search.md](../search.md)를 우선한다. S-03/S-06은 notificationConsent=true가 필수이며 501 골격 대상에서 제외한다.** store ST-01~20은 [store 구현 계약](../store.md)에 따라 영속 동작을 제공한다. notification N-01~06은 저장·읽음·SSE·FCM으로 구현했다. chatting은 501 계약 골격이다.

Spring Security가 `/api/v1/auth/**`, `/api/v1/users/**`의 세션 인증·CSRF를 적용한다. store의 공개 조회 5개를 제외한 15개 API에는 세션 쿠키·CSRF 및 선택적 Bearer JWT 인증을 적용한다. [store 인증 계약](../store-auth.md)에 적용 범위와 설정을 정의한다. search는 매장 검색을 공개하고 대기·분석 API는 현재 CUSTOMER 인증과 쿠키 변경 요청 CSRF를 검증한다. 내부 검색은 별도 내부 토큰을 유지한다([search 인증](../search-auth.md)). notification·chatting의 인증·권한은 해당 기능 구현 시 연결한다. `api-docs` 프로필은 문서 열람용으로 Spring Security 필터와 DB 의존성을 제외하며, search 자체의 요청자 검증은 유지한다. 자세한 로그인 설정은 [카카오 로그인](../kakao-login.md)을 따른다.
Spring Security가 `/api/v1/auth/**`, `/api/v1/users/**`, `/api/v1/notifications/**`의 세션 인증·CSRF를 적용한다. `SessionSearchUserAccess`는 search 공개 계약에 현재 세션·DB CUSTOMER 역할·동일 CSRF 저장소를 연결한다. store의 실제 카카오 인증 연결은 별도 작업이다. `api-docs` 프로필은 문서 열람용으로 Spring Security 필터와 DB 의존성을 제외하며, search 자체의 요청자 검증은 유지한다. 자세한 로그인 설정은 [카카오 로그인](../kakao-login.md)을 따른다.

## 이름 및 응답 규칙

- Controller는 리소스·호출 주체별로 구분한다. DTO는 `<동작><리소스>Request`, `<리소스>Response`, 목록은 `<리소스>ListResponse`를 사용한다.
- DTO는 도메인의 `dto` 패키지에 Java record로 정의한다. 공통 HTTP 봉투만 `common.dto`에 둔다. DTO를 도메인 엔티티로 공유하지 않는다.
- JSON 응답은 `BaseResponse<T>` 상속 기반의 `resultType/success.data/error/meta(timestamp,path)` 구조다. 기존 Notion의 최상위 data는 success.data로 이동한다. 목록의 items/page/counts 구조는 그 안에 유지한다. 204·OAuth redirect·SSE·WebSocket은 봉투 예외다. 자세한 계약은 [response.md](response.md)를 따른다.
- ID는 String, 오프셋 포함 시각은 OffsetDateTime, 날짜는 LocalDate, 금액은 정수 원이다. Web Push expirationTime은 Unix epoch 밀리초다.
- 사용자 API는 필수값·범위와 U-08의 필수 nullable `maxPrice` 키 존재 여부를 검증한다. 나머지 골격의 PATCH 생략/명시적 null 구분 및 필드 간 검증은 실제 구현 시 보완한다.
- Swagger 첫 화면의 `all` 그룹은 전체 HTTP API 48개를 표시한다. `public`은 46개, `internal`은 2개를 표시한다. 내부 계약은 외부 고객의 호출용이 아니며 search 내부 인증 설정은 [search.md](../search.md)를 따른다.
- Notion 예시에서 생략된 선택 필드(reason, openingHours 등)는 공통 규약을 기준으로 포함한다. ST-08은 OWNER 조회 시 customer/resourceId가 추가된다.

## 명세 간 차이

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

실시간 프레임은 [realtime.md](realtime.md)를 참고한다.

## 추가 store 등록 API

| 구분 | 도메인 | 메서드·경로 | Controller | 요청 DTO | 응답 DTO |
| --- | --- | --- | --- | --- | --- |
| ST-18 | store | POST `/api/v1/owner/stores` | OwnerStoreController.createStore | CreateStoreRequest | StoreDetailResponse |
| ST-19 | store | POST `/api/v1/owner/stores/{storeId}/services` | OwnerStoreController.createService | CreateStoreServiceRequest | StoreServiceResponse |
| ST-20 | store | POST `/api/v1/owner/stores/{storeId}/slots` | OwnerStoreController.createSlot | CreateSlotRequest | ReservationSlotResponse |

store MVP는 하나의 DB 트랜잭션에서 AVAILABLE → BOOKED로 확정하며 별도 HELD API를 도입하지 않는다. 예약 상태는 CONFIRMED와 후속 최종 상태로 구분한다. 한 매장의 동시 예약 자원은 1개다. store의 입력·권한·멱등성·마이그레이션·영속 작업·운영 인증 조건은 [store.md](../store.md)를 따른다.

### 사용자 선호 임베딩 연동

사용자 벡터 입력은 `user_accounts.preferences_json`의 자유로운 JSON 문자열 전체다. 내부 `UserPreferenceService`가 읽기/저장과 변경 이벤트를 제공하고 search가 OpenAI 호출/비동기 갱신/Oracle 벡터 저장을 맡는다. U-08은 categories·keywords·maxPrice·text와 사용자 버전을 저장하는 HTTP API로 구현했으며, 이 저장을 변경 이벤트 경로에 연결하는 작업은 아직 남아 있다. 다른 사용자의 선호 원문이나 벡터를 공개하는 새 HTTP API는 추가하지 않았다. 세부 오류와 마이그레이션/운영 명령은 [search 안내](../search.md)를 따른다.
