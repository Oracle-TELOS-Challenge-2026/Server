# Search 구현 현황과 후속 작업

기준일: 2026-10-16. 저장소의 실제 코드, 기존 테스트 결과와 이번 로컬 DB 반영 결과를 기준으로 작성했다. 우선순위는 다음 구현을 위한 제안이며, 상대 도메인의 미연결 계약을 완료로 계산하지 않는다.

이 문서는 구현 범위와 진행 상황을 관리한다. 세부 정책·설정·실행 명령·복구 SQL은 [Search 구현 및 운영](search.md), store 측 진행 상황은 [Store 구현 현황](store-progress.md)을 참고한다.

## 추가 결정: DB 단순화

대기·자동 알림 유지, Oracle 벡터 캐시 유지, 현재 동의만 저장하는 [v2 7개 테이블 구조](search-schema-v2.md)로 DDL·서비스·worker·시작 검증·테스트를 전환했다. 사용자 벡터 상태 3개를 하나로 합치고, 매칭 이벤트는 work로 통합하며, 매장 캐시는 벡터만 저장한다. 실제 store 조회·인증·알림 연결은 여전히 아래 P0 작업이다.

## 1. 현재 상태

**S-01~08의 Controller·Service·Repository와 OpenAI/Elasticsearch Adapter는 구현되어 있다. 사용자 선호 JSON으로 실제 OpenAI 임베딩을 생성하고 Oracle에 저장하는 경로도 검증했다. 실제 사용자 인증부터 매장 검색·매칭·알림 수신까지 이어지는 통합 흐름은 아직 미완료다.**

| 영역 | 구현 상태 | 남은 연결 |
| --- | --- | --- |
| 예약 대기 관리 | 조건·동의·버전·멱등성·만료·영속 작업 구현 | 실제 사용자 인증, 알림 설정, 매장 검증 |
| 조건 검색 | Elasticsearch nested 검색·정렬·커서·원장 재검증 구현 | 실제 store 조회와 전체/증분 데이터 공급 |
| 자연어 분석 | Responses 구조화 출력·서버 검증·추가 질문·장애 구분 구현 | 실제 사용자 요청 연결 및 자연어 품질 QA |
| 사용자 벡터 | 자유로운 JSON 조회 → 작업 저장 → 실제 OpenAI → Oracle 저장 구현 | 사용자 선호 저장 HTTP API |
| 매칭 | 모든 적격 고객 순회·제안 저장·고객별 통합·발송 전 재검증 구현 | store 이벤트 전달과 notification 접수 |
| 작업 복구 | DB claim·lease·재시도·중단 작업 회수 구현 | 운영 지표·보관 정책·장애 대응 검증 |
| 예약과 대기 완료 | store 예약 트랜잭션에서 search 협력 서비스 호출 구현 | 실제 검색·알림을 거친 예약 전체 QA |
| Oracle 스키마 | 로컬 앱 스키마에 search 테이블 추가 완료 | 다른 환경별 DDL 적용·확인 |

현재 `SearchConfiguration`의 기본 Bean은 미연결 기능을 성공으로 처리하지 않는다. `SearchUserAccess.customer`는 미인증 시 401, 인증 주체가 있어도 실제 계약 미연결 시 503을 반환한다. `notificationsEnabled`, `SearchStoreCatalog`의 5개 메서드, `SearchNotificationGateway.dispatch`는 503을 반환한다. 테스트의 대역으로 성공한 결과를 운영 연동 완료로 해석하면 안 된다.

## 2. API별 구현 범위

기존 URL과 응답 봉투를 유지한다. 아래의 “구현”은 서비스 로직과 테스트 범위이며 실제 연동 개방 여부는 위 표를 따른다.

| 번호 | API | 구현한 동작 |
| --- | --- | --- |
| S-01 | GET `/api/v1/search/stores` | 위치·반경·키워드·카테고리·시술 전체 시간·취소 슬롯 검색, 매장별 대표 슬롯, 거리/가격/추천 정렬, 5분 PIT 커서, 응답 직전 store 재검증 |
| S-02 | POST `/api/v1/search/intents/analyze` | 부분 조건 추출, RESOLVED/NEEDS_CLARIFICATION/UNSUPPORTED, 서버 검증, 외부 장애 503, 사용자 확인 전 대기 생성 금지 |
| S-03 | POST `/api/v1/search/waitlists` | 동의 필수, 조건 정규화, ACTIVE 중복·20개 제한, 사용자 잠금, 24시간 멱등성, 기존 슬롯 탐색·벡터 작업 등록 |
| S-04 | GET `/api/v1/search/waitlists/me` | 본인 목록, 상태별 개수, 만료 반영, 유효 제안 조회 |
| S-05 | GET `/api/v1/search/waitlists/{waitlistId}` | 본인 상세, AVAILABLE/WAITING/CLOSED, 재검증한 제안 시작 시각순 최대 3개 |
| S-06 | PATCH `/api/v1/search/waitlists/{waitlistId}` | ACTIVE 조건 전체 교체, expectedVersion·재동의, 이전 버전 제안 무효화, 재매칭 |
| S-07 | POST `/api/v1/search/waitlists/{waitlistId}/cancel` | 본인 취소, 버전·멱등성, 이미 취소한 대기의 반복 요청 200 |
| S-08 | POST `/api/v1/internal/search/match-jobs` | eventId 중복 제거, 동일 이벤트의 다른 내용 409, 영속 작업 등록 후 202, 비동기 매칭 |

S-08 HTTP는 내부 토큰 미설정 시 403이다. 단일 애플리케이션의 store 연동은 공개 `MatchService.submit`을 사용할 수 있다. 작업 등록 성공은 알림 발송이나 예약 확정을 뜻하지 않는다.

확정 정책은 다음과 같다.

- 시간·거리·가격·매장·시술 조건을 통과한 고객 전원이 알림 대상이다. top-5나 유사도 임계값을 적용하지 않는다.
- 시술 종료까지 희망 시간에 포함해야 한다. 매장·카테고리·시술 ID는 정확히 일치해야 한다.
- S-03/S-06은 `notificationConsent=true`가 필수다. nullable 필수 키도 누락과 null을 구분한다.
- S-01은 가격 정렬만 지원한다. 가격 상한은 대기 조건에서 적용한다.
- 벡터는 추천 순위에 사용한다. 벡터가 없거나 실패해도 필수 조건 매칭을 막지 않으며 추천 검색은 거리순으로 대체한다.

## 3. 사용자 벡터 구현

입력은 `user_accounts.preferences_json`의 자유로운 JSON 원문이다. 예를 들어 `{"memo":"네일에 관심이 있고 경력 3년 이상의 시술자가 있는 숍을 원해요"}`를 그대로 보낸다. 고정 선호 DTO로 변환하거나 대기 조건을 덧붙이지 않는다. 경력 같은 자연어 요구는 느슨한 선호 유사도이며 정확한 필수 필터로 보장하지 않는다.

처리 흐름:

1. user의 `UserPreferenceService.replace`가 유효한 JSON을 저장하고 변경 이벤트를 발행한다.
2. search가 같은 트랜잭션에 선호 revision과 VECTOR 작업을 저장한다. 작업 저장 실패 시 선호 변경도 롤백한다.
3. worker가 `SearchUserAccess.explicitPreferences`로 원문을 읽는다. 동일 입력·모델·차원의 정상 벡터는 재사용한다.
4. `.env`의 `OPENAI_API_KEY`로 `POST /v1/embeddings`를 호출한다. 기본 모델은 `text-embedding-3-small`, 차원은 1,536이다.
5. 응답 검증 후 원문 해시·revision·lease owner를 다시 확인하고 Oracle `search_user_vector`에 저장한다. 검색에서는 이 벡터를 Elasticsearch에 전달한다.

빈 선호는 기존 벡터를 제거한다. UTF-8 8,000바이트 초과 입력은 자르지 않고 거절한다. 사용자별 30초 생성 lease로 동시 호출을 제어하며, 외부 API 호출 동안 DB 잠금을 유지하지 않는다. 영구 오류와 일시 오류의 재시도를 구분한다. 키·선호 원문·벡터는 로그에 출력하지 않는다.

**U-08 선호 저장 HTTP API는 여전히 기존 DTO/501 골격이다.** 현재 구현된 내부 저장 서비스와 벡터 생성 기능을 실제 사용자 화면에서 쓰려면 해당 계약을 자유 JSON에 맞추고 `replace`로 연결해야 한다. 최초 생성·수동 재생성 명령은 [운영 문서](search.md#사용자-벡터-생성-실행)에 있다.

## 4. 영속화와 DB 반영

Oracle이 원본이고 Elasticsearch는 재구축 가능한 검색 인덱스다. `ddl-auto=validate`를 유지하며 JDBC 테이블은 `SearchSchemaVerifier`가 별도로 확인한다.

| 테이블 | 책임 |
| --- | --- |
| `search_user_lock` | 대기 변경 직렬화용 256개 잠금 버킷 |
| `search_intent` | 현재 조건·동의 시각/버전·상태·예약 연결 |
| `search_idempotency` | 생성·취소의 24시간 최초 응답 재생 |
| `search_work` | 영속 작업·재시도·lease·슬롯 이벤트 중복 제거·공개 jobId |
| `search_offer` | 대기 버전별 슬롯 제안 |
| `search_user_vector` | 사용자 벡터 캐시·현재 선호 revision·생성 lease |
| `search_store_vector` | 매장 설명 벡터 캐시 |

신규 환경은 [search-oracle.sql](sql/search-oracle.sql)을 사용한다. 기존 v1의 11개 테이블은 [전환 절차](search-schema-v2.md)에 따라 데이터 복사 후 7개로 전환한다. 기존 `search_waitlists`는 별도로 유지하며 예약 협력 서비스가 지원한다. 최종 앱 테이블은 기존 13개 + search 7개 = 20개다. 업무용 테스트 데이터는 이번 전환에서 추가하지 않는다.

## 5. 검증 현황

기존 구현 단계의 검증과 이번 문서화 작업의 검증을 구분한다.

| 검증 | 확인된 결과 | 범위·제한 |
| --- | --- | --- |
| `./gradlew format test build --console=plain` | 기존 구현 단계와 v2 코드 전환 후 성공 | v1 당시 기본 테스트는 70건 중 실패 0, 외부 검증 3건 skip. v2 코드 전환 후 전체 테스트·빌드도 재실행해 통과 |
| `SearchBehaviorTests` | 서비스·HTTP 동작 검증 | 동의/시간/가격/소유권/중복/20개 경쟁/멱등/만료/7명 전원/다중 대기 통합/취소 후 발송 차단 등, 도메인 연동은 테스트 대역 |
| `OpenAiIntentAdapterTests` | 외부 프로토콜·오류 검증 | 구조화 출력·임베딩 응답·키 누락·시간 초과, 실제 자연어 품질 평가는 아님 |
| `UserVectorFlowTests` | H2 및 격리 Oracle에서 검증 | JSON 원문·재사용·롤백·동시 생성·lease 회수·오래된 응답 차단·오류 분류 |
| `SearchOracleTests` | 별도 Oracle 23 Free 스키마에서 검증 | CLOB·잠금·20개 경쟁·멱등·이벤트 제약, 기본 실행에서는 skip |
| `SearchElasticsearchTests` | 실제 Elasticsearch 8.19.6에서 검증 | nested 조건·가격/벡터 정렬·PIT/커서·마감 슬롯 재검증, 기본 실행에서는 skip |
| `testOpenAi` | 실제 키로 합성 선호 JSON 임베딩 1건 성공 | H2 및 별도 Oracle에서 실제 벡터 저장 확인, 유료 호출을 기본 테스트에서 제외 |
| v2 로컬 DB 전환 | 24개 → 20개, search 7개·잠금 256행, 기존 13개 테이블 행 수 보존 | 코드 전환 후 format/test/build 및 격리 Oracle 대기·벡터 테스트 통과 |

아직 실제 로그인 → 선호 저장 → 대기 등록 → 매장 빈자리 발행 → 전원 매칭 → 실제 알림 → 예약 확정까지의 통합 검증은 하지 않았다. 실제 Responses API의 한국어 분석 품질, 다중 인스턴스 강제 종료 복구, 운영 부하도 별도 검증 대상이다.

## 6. 다음 구현 우선순위

### P0-1. 실제 store 조회 계약 연결 — 가장 먼저 진행

`SearchStoreCatalog`의 `validate`, `available`, `revalidate`, `describe`, `export`를 store 공개 서비스로 구현한다. search에서 store Repository를 직접 호출하지 않는다. 전체/증분 문서의 버전은 매장·시술·슬롯 중 검색 결과에 영향을 주는 모든 변경에서 증가하도록 정한다.

완료 기준: 실제 Oracle 매장·시술·슬롯으로 S-01 검색, 대기 등록 검증, 기존 슬롯 탐색, 마감 슬롯 제외가 동작한다. export/available의 마지막 커서까지 누락 없이 읽으며, 특정 매장·시술 불일치를 거절한다.

### P0-2. store 이벤트 전달과 검색 인덱스 동기화

store가 저장하는 이벤트를 durable consumer로 전달해 `SearchIndexService.changed`와 `MatchService.submit`을 호출한다. 발행·철회·예약·만료·매장/시술 변경을 반영하고 삭제/비공개는 tombstone으로 처리한다. 초기 재구축은 `rebuild()`를 사용하되 재구축 중 변경을 놓치지 않도록 재전송·진행 위치·재시도 경계를 연결한다.

완료 기준: 중복·역순 이벤트와 consumer 재시작에도 최신 검색 문서가 유지된다. 발행 이벤트 한 건은 같은 jobId를 재사용하고, 예약·철회된 슬롯은 검색과 알림 후보에서 빠진다. 인덱스 재구축 중 변경도 전환 후 반영된다.

### P0-3. user 인증·알림 설정·선호 저장 API

공통 세션 인증/CUSTOMER 역할/CSRF와 `SearchUserAccess.customer`, `notificationsEnabled`를 연결한다. U-08의 기존 정형 DTO 계약을 검토해 자유 JSON 저장 계약·Swagger·테스트를 함께 갱신하고 `UserPreferenceService.replace`를 호출한다. 공개 검색의 인증 사용자 식별도 함께 확인한다.

완료 기준: 실제 로그인 사용자는 본인 대기만 조회·변경할 수 있다. 인증·역할·CSRF 실패를 구분하고, 선호 저장 후 별도 수동 명령 없이 VECTOR 작업이 생성된다. 선호 변경 중 이전 응답은 저장되지 않는다.

### P0-4. 실제 notification 접수와 전달

`SearchNotificationGateway.dispatch`를 N-06 접수에 연결한다. notification이 user+slot 중복 방지·사용자 알림 설정·발송 제한·대기 버전/동의·슬롯 최종 유효성을 확인하고 실제 전달을 책임진다.

완료 기준: 5명을 넘는 적격 고객 전원이 처리되고, 한 사용자의 여러 대기는 하나의 알림 요청으로 합쳐진다. 재시도해도 중복 알림을 보내지 않으며 취소·수정·예약·철회된 조건의 알림은 발송되지 않는다.

### P0-5. 실제 도메인 통합 시나리오 검증

위 연결 후 로그인·선호·대기·발행·매칭·알림·예약 확정의 단일 흐름과 실패 경계를 검증한다. 예약에 의한 `FULFILLED`와 슬롯 마감까지 확인한다. 격리된 QA 데이터와 재실행 가능한 절차를 남긴다.

완료 기준: 정상 흐름 외에도 중복 이벤트, 두 고객의 동시 예약, 발송 직전 취소, worker 중단/회수, OpenAI 장애 중 수동 대기·조건 매칭 지속을 검증한다.

### P1. 운영·품질 보강

- 작업 대기 시간·실패/재시도·회수 건수·인덱스 지연을 관측하고 실패 작업 재처리 절차를 실제 장애 주입으로 확인한다.
- 종료 작업·이전 제안·멱등 기록의 보관 기간과 삭제 절차, 미사용 인덱스 정리 정책을 결정한다.
- 자연어 분석의 모호한 장소·시술·시간에 대한 실제 한국어 QA를 수행한다. 장소 확정과 시술 선택은 서버의 실제 데이터와 사용자 확인을 따른다.
- 사용자·매장 임베딩 모델 변경 시 동시 재생성·인덱스 재구축·복구 절차를 정하고 다중 인스턴스 부하·강제 종료를 검증한다.
- 레거시 `search_waitlists`의 데이터 이전·정리 여부와 업무용 테스트 데이터 시나리오를 별도 합의한다.

## 7. 주요 코드 위치

| 책임 | 코드 |
| --- | --- |
| 기본 연동 경계와 미연결 거절 | [SearchConfiguration](../src/main/java/com/bintime/search/config/SearchConfiguration.java) |
| 조건 판정·대기 관리 | [ConditionRules](../src/main/java/com/bintime/search/service/ConditionRules.java), [WaitlistService](../src/main/java/com/bintime/search/service/WaitlistService.java) |
| 검색·커서·인덱스 | [StoreSearchService](../src/main/java/com/bintime/search/service/StoreSearchService.java), [SearchIndexService](../src/main/java/com/bintime/search/service/SearchIndexService.java) |
| 자연어·개인화 | [IntentAnalysisService](../src/main/java/com/bintime/search/service/IntentAnalysisService.java), [PersonalizationService](../src/main/java/com/bintime/search/service/PersonalizationService.java), [OpenAiIntentAdapter](../src/main/java/com/bintime/search/adapter/OpenAiIntentAdapter.java) |
| 선호 원본 저장 | [UserPreferenceService](../src/main/java/com/bintime/user/service/UserPreferenceService.java) |
| 매칭·작업 복구 | [MatchService](../src/main/java/com/bintime/search/service/MatchService.java), [SearchWorker](../src/main/java/com/bintime/search/service/SearchWorker.java) |
| Oracle 저장·시작 검증 | [SearchRepository](../src/main/java/com/bintime/search/repository/SearchRepository.java), [SearchSchemaVerifier](../src/main/java/com/bintime/search/config/SearchSchemaVerifier.java) |
| 예약 완료 협력 | [WaitlistReservationService](../src/main/java/com/bintime/search/service/WaitlistReservationService.java) |
