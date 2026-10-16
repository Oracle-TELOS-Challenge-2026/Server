# Store · Search · Notification 통합

2026-10-16 기준. 현재 알림 계약은 [실시간 전송 계약](contracts/realtime.md)을 따른다.

## 연결 경로

| 단계 | 실제 구현과 책임 |
| --- | --- |
| 매장 공급 변경 | `StoreApplicationService`가 업무 변경과 `store_events`를 같은 트랜잭션에 저장 |
| 커밋된 이벤트 전달 | `StoreEventDelivery`가 `SearchIndexService.changed`와 생성·발행의 `MatchService.submit`을 접수하고 ack를 함께 커밋 |
| 매칭 | `SearchWorker`가 `SearchStoreCatalog`의 실제 원장과 활성·동의 대기 조건을 검사하고 NOTIFY 작업 저장 |
| 알림 수신 가능 여부 | `SearchUserAccessAdapter.notificationsEnabled`가 `NotificationSubscriptions.hasActive`로 현재 로그인 세션에 연결된 FID 확인 |
| 알림 접수 | `SearchNotificationGateway`의 실제 구현인 `NotificationService`가 대기·발행 버전·조건을 재검증하고 알림, 기기별 발송 작업, `notification.created` 이벤트 저장 |
| 전달 | `NotificationStream`이 저장된 SSE 이벤트를 전달. `NotificationDeliveryWorker`는 발송 직전 대기·슬롯·구독을 재확인하고 FCM 제출·재시도 |
| 예약 | store 예약에서 연결 대기를 같은 DB 트랜잭션으로 완료. 예약된 슬롯은 후속 알림 재검증에서 제외 |

store가 N-06을 직접 호출하거나 알림 테이블을 수정하지 않는다. search는 같은 앱의 공개 Java 계약을 호출하므로 내부 HTTP 토큰이 필요하지 않다. 외부에서 N-06 HTTP를 사용할 때만 `X-Internal-Token` 검증이 적용된다.

`SearchStoreCatalog`는 `StoreSearchCatalogService` 하나로 연결하며 알림 도메인에 별도 store 대체 어댑터를 등록하지 않는다. 사용자 경계도 세션/JWT 공통 인증과 구독 조회를 제공하는 `SearchUserAccessAdapter` 하나를 사용한다.

## 벡터 기준과 전달 전 재검증

사용자 선호와 매장·시술 설명의 코사인 유사도가 `SEARCH_MATCHING_MINIMUM_SIMILARITY`(기본 0.5) 이상이어야 한다. 카테고리·시간·가격이 동일해도 점수 미달 고객은 알림을 저장하거나 발송하지 않는다. 벡터 미준비는 생성을 요청하고 접수를 보류한다. notification의 접수 및 최종 FCM guard에서도 현재 벡터를 검사하므로 내부 N-06 직접 호출로 우회할 수 없다. 자세한 점수·캐시·실패 복구 의미는 [search 정책](search.md#알림-대상의-벡터-유사도-기준)을 따른다.

## 상태와 중복 처리

- `matchingStatus=QUEUED`는 store outbox 저장 완료다. `store_events.PROCESSED`도 search 큐 접수 완료이며, 실제 푸시 수신 완료가 아니다.
- 알림 접수는 `eventId`, 사용자·슬롯 ID, `publicationVersion`, 일치 대기 ID·버전, 만료 시각을 전달한다. 일반 슬롯의 발행 버전은 null이다.
- 동일 사용자·슬롯의 알림은 한 번만 저장한다. 재발행은 이전 작업의 발행 버전을 무효화하지만 이미 알림을 저장한 사용자에게 같은 슬롯의 새 알림을 만들지 않는다. search 제안은 현재 발행 버전으로 갱신한다.
- 활성 구독이 없으면 알림 저장을 건너뛴다. FID를 나중에 등록해도 이전에 건너뛴 푸시를 자동 재생하지 않는다.
- 예약·철회·만료 후 대기 중인 발송은 건너뛰며, 기존 알림 이력은 보존한다. 목록을 다시 조회하면 `availability=UNAVAILABLE`이다. 외부 원장 조회 장애는 `UNKNOWN`이다.
- 현재 SSE 계약은 생성·읽음·세션 만료만 제공한다. 슬롯 마감 전용 SSE 이벤트는 없으며, 열린 화면은 슬롯·알림을 재조회해야 한다. 이미 FCM에 제출된 메시지는 회수할 수 없다.

## 자동 통합 검증

```bash
./gradlew test --tests 'com.bintime.store.StoreNotificationIntegrationTests' --no-daemon
```

테스트는 각자 격리된 H2 DB에서 실제 store·search·notification 서비스와 Repository를 사용한다. 직접 N-06을 호출하지 않고 취소 빈자리 발행 → outbox 소비 → 매칭 → 알림 접수 → 발송 작업까지 진행한다. 구독 등록·알림 목록·대기를 연결한 재예약은 세션·CSRF를 적용한 실제 MVC 경로도 확인한다. 기준 위치는 `37.51335413975466, 126.92649449142719`다.

검증 시나리오:

1. 필수 조건과 벡터 유사도 기준을 통과한 고객 전원에게 알림 생성. 가격 불일치·유사도 미달·미구독 고객 제외. 이벤트 재전달에도 저장·발송·SSE 생성 이벤트 중복 방지.
2. 알림을 받은 고객이 빈자리를 예약하면 연결 대기가 완료되고 대기 중인 다른 고객의 푸시도 차단.
3. 알림 접수 뒤 철회하면 미발송 작업 차단·목록 마감 반영.
4. 슬롯 시작 시각에 만료하면 미발송 작업 차단·목록 마감 반영.
5. 발행 직후 철회한 이벤트가 늦게 처리되면 알림을 생성하지 않음.
6. 재발행 시 이전 푸시 차단·제안 버전 갱신·사용자+슬롯 중복 방지 유지.
7. FCM 일시 실패 후 같은 알림 ID로 재시도하고 정상 접수.

실제 외부 공급자인 FCM·OpenAI·Elasticsearch만 테스트 대역으로 바꾼다. 스케줄러를 기다리지 않고 동일 소비자·worker 메서드를 직접 호출하고 테스트 시계를 이동한다. Oracle·ES 별도 검증은 [store/search 통합 검증](store-search-integration.md)에 기록했다. 이번 알림 통합 테스트를 실제 Oracle·FCM 기기 수신 또는 브라우저 SSE 수신 검증으로 간주하지 않는다. SSE 전송·재생과 FCM HTTP 오류 처리는 기존 `NotificationStreamTests`, `FcmClientTests`에서 별도 검증한다.

검증 기록(2026-10-16): 위 통합 테스트 7건 통과. 전체 format·test·build도 성공했으며 총 244건 중 240건 통과, 외부 연동 조건부 테스트 4건 제외다. 병렬로 실행 중인 다른 검증과 결과 파일이 충돌하지 않도록 Gradle init script로 빌드 출력만 임시 경로로 분리했다.

## 로컬·배포 준비

기존 store DB는 06·07 변경 SQL, 알림 DB는 `002-notification-subscriptions.sql`, `003-notification-delivery.sql`이 필요하다. 이 테스트는 앱 DB에 SQL을 적용하지 않는다. 적용 순서와 FCM 서비스 계정·환경 변수는 [인프라 안내](../infra/README.md#fcm-알림-설정)를 따른다.

실제 수신 QA에는 로그인한 브라우저의 FID 등록, 활성 대기의 명시적 동의, FCM 설정과 실행 중인 소비자가 필요하다. 기본 `FCM_ENABLED=false`는 외부 발송을 하지 않으며 접수한 발송 작업을 보존한다. 로그인·FID 연결은 현재 단일 앱 프로세스의 세션 수명에 종속되므로 재시작 후 다시 등록한다.

추가 H2 통합 시나리오: 동일 NAIL·시간·가격 조건에서 사용자 벡터만 다른 두 고객의 알림 포함/제외, OpenAI 실패 중 발송 보류와 회복 후 중복 없는 전달, 알림 저장 후 사용자 선호 또는 매장 설명 변경 시 오래된 벡터 및 FCM 제출 차단을 검증한다. 임베딩·FCM 외부 API는 대역을 사용한다.
