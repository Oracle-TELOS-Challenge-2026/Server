# Search → Notification 연결 및 검증

기준일: 2026-10-16. 예약 대기와 앱 전체 동의 기반 맞춤 알림을 지원하며 선호 수정 시 벡터를 자동 갱신한다.

## 연결한 경계

- `StoreEventDelivery` → `SearchIndexService.changed` / `MatchService.submit`: 커밋된 매장 변경을 search 영속 작업으로 접수한다.
- `SearchWorker` → `SearchUserAccessAdapter.notificationsEnabled`: 유효한 로그인 세션에 연결된 활성 알림 구독을 확인한다. 구독이 없으면 발송하지 않는다.
- `SearchWorker` → `SearchNotificationGateway.dispatch`: 정상 프로필에서는 `NotificationService`를 필수 사용한다. 미연결 대체 Bean은 `api-docs` 전용이다.
- `NotificationService` → store/search 공개 조회: 대기 소유자·ACTIVE·버전·조건과 슬롯 발행 버전을 다시 검사한다. 사용자+슬롯 중복 방지와 알림·전달 큐·SSE 이벤트 저장은 notification 책임이다.
- `NotificationDeliveryWorker` → `FcmClient`: 재시도·사용자별 발송 간격과 활성 구독·대기·슬롯을 확인한 뒤 제출한다. 외부 FCM 제출 중 DB 잠금을 유지하지 않는다.

`NotificationService`가 사용하는 store 카탈로그는 실제 `StoreSearchCatalogService`다. notification의 불완전한 별도 카탈로그나 중복 사용자 인증 Adapter를 추가하지 않는다. search·notification 모두 다른 도메인의 Repository를 직접 호출하지 않는다.

동의는 현재 대기의 조건 버전에 저장한다. 알림 구독은 별도의 브라우저/세션 등록이다. 대기 동의만으로 활성 구독을 만들어내지 않는다. 같은 슬롯에 여러 대기가 일치하면 고객별 한 알림 요청의 matches로 묶는다. 추천 점수·top-k로 적격 고객을 제외하지 않는다.

## DB와 시작 검증

기존 DB에는 다음 notification 마이그레이션을 앱 스키마 계정으로 순서대로 실행한다. 스크립트가 USER_TABLES/USER_TAB_COLUMNS를 조회하므로 SYS의 CURRENT_SCHEMA만 바꾸는 것으로 대체하지 않는다. SQL*Plus 스크립트의 SET/WHENEVER 구문은 JDBC SQL이 아니므로 IDE에서는 스크립트 실행 방식에 맞춰 처리한다.

1. `infra/oracle/migrations/002-notification-subscriptions.sql`
2. `infra/oracle/migrations/003-notification-delivery.sql`

search v2 DDL과 기존 user/store 스키마를 먼저 준비한다. 쓰기 중지·백업 후 적용한다. 구 Web Push 구독은 보존하되 비활성화하며 로그인 브라우저에서 새 구독이 필요하다. 기본 초기 DDL만 실행한 새 환경에도 이 notification 마이그레이션이 필요하다.

`NotificationSchemaVerifier`는 정상 기동에서 알림 JDBC 테이블 7개의 필수 열을 확인한다. DDL을 자동 생성하거나 실제 전송을 켜지 않는다. 기본 활성이고 DB 없는 api-docs에서는 실행하지 않는다. 테스트는 스키마를 직접 준비하므로 `notification.schema-validation-enabled=false`를 사용한다. 연결 통합 테스트에서는 fixture 준비 후 검증기를 직접 실행한다.

로컬 `FREEPDB1/BINTIME_APP`에 두 migration을 실제 앱 계정으로 적용했다. 기존 20개 테이블의 행 수를 유지하고 다음 5개를 추가해 **총 25개**다. search 테이블 수는 7개로 유지된다.

- `notification_subscription_sessions`
- `notification_dedup`
- `notification_events`
- `notification_deliveries`
- `notification_delivery_clock`

적용 전 알림/구독 업무 행은 각각 0개였다. 적용 전 테이블별 행 수와 실행 SQL은 `/private/tmp/bintime-notification-before-20261016-232927`에 보관했다. 앱 DB에 합성 고객·구독·알림을 넣지 않았다.

## 검증 결과

`SearchNotificationConnectionTests`는 store 이벤트 consumer, 실제 search 매칭 worker, 실제 사용자 구독 조회, NotificationService와 전달 worker를 연결한다. 외부 OpenAI/Elasticsearch/FCM만 대역이다.

- 7명의 고객과 고객별 2개 대기가 한 슬롯에 일치하면 알림은 고객별 하나, 총 7개이며 matches는 각각 2개다.
- 구독 없는 일치 고객은 알림 저장·발송에서 제외한다.
- 이벤트/worker 재처리 후에도 알림과 FCM 제출 수가 늘어나지 않는다.
- 새 슬롯의 제안을 접수한 뒤 대기 취소 및 예약이 발생하면 전달은 SKIPPED이고 FCM에 제출하지 않는다.
- 예약에 연결한 대기는 FULFILLED가 된다.
- 별도 `StoreNotificationIntegrationTests`와 notification 단위 테스트가 발행·철회·재발행·만료·일시 실패 재시도 경계를 검증한다.

동일 연결 테스트를 별도 Oracle 스키마에서도 실행했다. 초기 DDL과 migration을 실제 스키마 계정으로 적용·재실행하고, CLOB 저장·잠금·큐 상태까지 검증했다. Oracle에서 미수락 발송 결과를 저장할 때 발생한 ORA-00932를 수정했다. `COALESCE`에 전달하는 nullable 수락 시각을 `TIMESTAMP WITH TIME ZONE`으로 명시한다. 테스트 스키마는 정리했다.

기본 검증은 `./gradlew format test build --console=plain`의 작업들을 실행했다. 동시 검증과 파일 충돌을 피하려고 이번에는 임시 Gradle init script로 build 경로만 `/private/tmp/bintime-notification-connect-build`로 분리했다. **244건 중 실패/오류 0, 외부 검증 4건 skip**, 빌드 성공이다.

로컬 실제 앱 DB에서는 자동 작업과 FCM 전송을 끈 상태로 기동해 시작 스키마 검증을 통과했다. 공개 검색 200, 미인증 알림 API 401을 확인하고 점검 서버를 종료했다. 이번 작업에서 실제 FCM 자격증명·실기기 푸시는 사용하지 않았다.

## 실행 및 다음 확인

기본 테스트는 외부 호출 없이 실행한다.

```bash
./gradlew test --tests '*SearchNotificationConnectionTests'
```

Oracle 연결 테스트는 빈 전용 스키마에 최신 초기 DDL, search DDL, user-profile migration, notification migration 002/003을 적용한 뒤 실행한다. `NOTIFICATION_ORACLE_USER`는 `BT_NOTIFY_` 뒤에 대문자 16진수 12자리인 전용 테스트 계정만 허용한다. 앱 스키마를 사용하지 않는다.

```bash
NOTIFICATION_ORACLE_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 \
NOTIFICATION_ORACLE_USER=<disposable-user> NOTIFICATION_ORACLE_PASSWORD=<secret> \
./gradlew test --tests '*SearchNotificationConnectionTests'
```

다음 MVP 확인은 실제 브라우저에서 로그인 → 알림 구독 등록 → 대기 활성화 → 슬롯 발행 → SSE/푸시 확인 → 예약 확정이다. FCM을 사용하는 환경에서는 [FCM 실행 설정](../infra/README.md#fcm-알림-설정)에 따라 서비스 계정·프로젝트·프런트 등록값을 준비한다. FCM 수락은 화면 표시나 실제 사용자 수신 완료를 뜻하지 않으므로 브라우저에서 확인한다. 로그아웃·세션 만료 후의 구독 재등록 동작도 확인한다.

사용자 선호 변경 시 벡터 자동 갱신과 추천 품질 보강은 이 흐름 검증 이후의 후순위 작업이다. 운영 DB 마이그레이션·Compose 배포·실기기 수신은 아직 수행하지 않았다.

맞춤 알림의 새 자격 경로는 [search 계약](search.md#예약-대기-없는-맞춤-빈자리-알림)을 따른다. 대기 없는 사용자도 명시적 앱 전체 동의·활성 구독·선호 유사도 기준을 통과하면 알림을 받는다. 현재 시연은 프로필 위치를 무시한다. 기존 대기 경로의 조건은 유지한다.
