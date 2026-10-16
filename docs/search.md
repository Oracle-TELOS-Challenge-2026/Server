# Search 구현 및 운영

구현 완료 범위·미연결 의존성·검증 이력·다음 작업 우선순위는 [Search 구현 현황과 후속 작업](search-progress.md)에 정리한다.

## DB 구성

대기·자동 알림과 Oracle 벡터 캐시는 유지하고, 동의는 현재 조건만 저장한다. [7개 테이블 DDL 및 전환 절차](search-schema-v2.md)를 따른다. 사용자 벡터·선호 revision·생성 lease는 `search_user_vector`에 통합했고, 매장 전체 문서를 Oracle에 복제하지 않고 `search_store_vector`에 벡터만 캐시한다. 슬롯 이벤트의 중복 제거와 공개 jobId는 `search_work`가 관리한다.

## 범위와 의존 작업

S-01~08은 MVC Service/JDBC Repository와 OpenAI·Elasticsearch Adapter로 처리한다. Oracle이 원본이며 Elasticsearch는 파생 인덱스다. 세션/JWT 인증·매장 카탈로그·구독 활성 여부·알림 접수는 아래 공개 계약으로 연결했다. 외부 서비스 설정은 별도 준비가 필요하며 미연결 기능은 503으로 거절한다. 테스트 대역은 `src/test`에만 있다.

- user: `SearchUserAccessAdapter`는 공통 인증 경계로 세션/JWT·현재 CUSTOMER 원장 역할을 확인하고 쿠키 CSRF는 공통 보안 필터가 검증한다. `notificationsEnabled`는 `NotificationSubscriptions.hasActive`로 유효한 세션에 연결된 FID 유무를 확인한다. `explicitPreferences`는 `UserPreferenceService`를 통해 `user_accounts.preferences_json` 전체를 조회한다. `UserPreferenceService.replace`는 자유로운 JSON 저장과 동기 `UserPreferencesChanged` 이벤트를 같은 트랜잭션에서 처리하며, search 소비자는 revision과 VECTOR 작업을 같은 트랜잭션에 기록한다. U-04~08 프로필·위치·선호 저장 HTTP API는 구현되어 있다. U-08 저장을 이 변경 이벤트 경로에 연결하는 작업은 아직 남아 있다.
- store: `StoreSearchCatalogService`가 `SearchStoreCatalog` 5개 메서드를 구현하며 정상 프로필에서 search에 주입된다. 503 대체 구현은 DB 없는 `api-docs`에서만 사용한다. 기존 store DB에는 migration 06이 필요하다. `SearchStoreCatalog.validate`는 실제 매장·카테고리·시술 관계를 검증한다. `describe`는 표시용 이름을 제공한다. `available`과 `export`는 cursor가 null일 때 첫 페이지이며 nextCursor=null까지 누락 없이 순회한다. `revalidate`는 현재 AVAILABLE이며 미래인 슬롯만 반환한다. 슬롯 start/end와 가격은 실제 원장 값이다. 문서 version은 매장·서비스·슬롯의 모든 검색 관련 변경마다 증가한다.
- store 커밋 후 `MatchService.submit(eventId, slotId, publicationVersion)`와 `SearchIndexService.changed(전체문서)`를 생산자 outbox 등으로 재전달해야 한다. 슬롯 삭제/매장 비공개는 slots=[] tombstone을 전달한다. 예약 완료는 user/intent/version/reservationId를 담아 `WaitlistService.fulfilled`로 전달한다. 검색 도메인이 예약을 확정하지 않는다.

- notification: `NotificationService`가 `SearchNotificationGateway.dispatch`의 N-06 DTO를 접수하고 사용자·슬롯별로 알림을 한 번 저장한다. 접수 시 유효성은 조회 시점의 스냅샷이며 저장된 알림은 이후 취소해도 이력에 남는다. 매 발송 직전 활성 대기·동의에 연결된 버전·조건·슬롯 및 세션을 다시 확인한다. 이미 시작한 외부 HTTP 전송과 취소를 원자적으로 묶지는 않으며 검색 DB 잠금을 외부 호출 동안 유지하지 않는다.

store의 조회 계약은 `StoreSearchCatalogService` 하나로 연결한다. 필터·커서·검색 전용 버전 및 06 DB 변경은 [store 카탈로그 계약](store-catalog.md)을 참고한다. store 커밋 이벤트도 [전달 소비자](store-event-delivery.md)가 search 큐로 넘긴다(기존 DB는 07 적용). [search 사용자 인증](search-auth.md)은 세션·JWT와 현재 CUSTOMER 원장 역할에 연결되었다.

기존 JPA Waitlist(`search_waitlists`)와 새 `search_intent`는 별도 저장 모델이다. `WaitlistReservationService`는 두 모델을 지원하며 store 예약과 같은 트랜잭션에서 조건 검증과 완료 처리를 수행한다. search는 다른 도메인의 Repository를 호출하지 않는다.

## 확정한 정책과 HTTP 계약 변경

- 필수 조건에 일치하는 고객 전원이 대상이다. top-5/top-k나 유사도 임계값은 없다. 여러 대기가 일치하면 N-06 matches에 ID/버전을 묶는다.
- 시술 시작부터 종료까지 희망 구간에 포함한다. 동일 instant의 서로 다른 offset은 동일하게 판단한다. 거리 계산은 지구 반경 6,371,008.8m의 구면 직선거리다. 가격 경계는 포함하며 원 단위 KRW 정수다.
- 매장/카테고리/시술 ID는 정확히 일치한다. keywords는 필수 필터가 아니다. 특정 매장 이외 대체 매장을 자동 추천하지 않는다.
- **S-03/S-06에 notificationConsent=true가 필수로 추가됐다.** 기존 클라이언트 변경이 필요하다. 조건 전체 교체 시 동의 시각·조건 버전을 현재 대기 행에 갱신하며 과거 동의 이력은 저장하지 않는다. WaitlistConditions의 nullable 필드와 S-08 publicationVersion도 키를 반드시 보내야 하며 null과 누락은 다르다.
- S-01 기존 query를 유지한다. 가격 상한은 없고 PRICE 정렬만 지원한다. maxPrice는 대기 조건에만 적용한다. walkingMinutes=null이다.
- S-01은 매장 단위 nested 슬롯 필터와 PIT/search_after를 사용한다. PRICE는 필터 통과 슬롯의 최저 가격, DISTANCE는 거리, RECOMMENDED는 실제 매장·시술 설명의 벡터 유사도다. 동률은 거리, 일치 슬롯 시작 시각, 매장 ID로 정렬하고 대표 슬롯은 가격순에서는 최저 가격, 나머지는 가장 빠른 슬롯이다. 유사도는 예약 확률이 아니다.
- 후보 슬롯은 응답 직전 store에서 일괄 재검증한다. 원장 버전이 바뀌거나 마감된 슬롯은 제거하고 다음 후보를 계속 읽는다. 마지막 확인 이후의 예약 경쟁은 store 책임이다.
- 커서는 HMAC으로 서명하며 최초 발급 시각부터 5분 유효하다. 조건/정렬/사용자/개인화 벡터 변경·변조·만료를 거절한다. 조회 도중 갱신된 PIT ID를 이어받고 완료 시 닫는다. limit은 변경 가능하다. 익명·벡터 없음·벡터 생성 실패는 거리순이다. 최초 벡터 검색 장애도 거리순으로 재시도한다. 이미 페이지를 발급한 뒤 정렬 장애가 발생하면 순서를 바꾸지 않고 503을 반환한다.
- S-03/S-06 응답은 커밋된 조건을 즉시 반환한다. 기존 슬롯 탐색과 벡터 생성은 영속 worker 작업으로 이어진다. 상세/목록은 현재 조건 버전의 제안을 store에서 재검증하고 시작 시각순 최대 3개를 제공한다. 표시용 이름은 store describe 계약으로 조회한다.
- ACTIVE 20개와 중복을 사용자 잠금으로 직렬화한다. 256개 사전 생성 잠금 버킷을 사용하므로 같은 버킷의 다른 사용자도 짧게 직렬화될 수 있다. 이 방식은 첫 등록의 잠금 행 생성 경쟁을 피한다.
- 생성/취소 멱등키는 사용자·작업 단위 24시간이다. 동일 요청은 최초 응답을 재생한다. 다른 요청은 IDEMPOTENCY_CONFLICT다. 취소된 대기의 반복 취소는 오래된 expectedVersion이어도 200이다.
- 종료 시 EXPIRED, 예약 완료 통지는 FULFILLED로 처리한다. 조건 버전이 바뀌면 이전 제안과 이전 알림 작업은 더 이상 유효하지 않다. 제안은 선점을 뜻하지 않는다.
- 인증 실패 401, 권한/CSRF/내부 인증 실패 403, 입력/커서 400, 본인 리소스 없음 404, 중복/상태/버전/멱등 충돌 409, 외부 의존 기능 장애 503이다. 503의 error.retryable=true이며 나머지 도메인 오류는 false다. 응답 봉투와 기존 URL을 유지한다.

## AI와 개인화

OpenAI Responses의 strict JSON Schema로 부분 조건을 추출한다. 기준 시간은 주입된 Clock의 instant와 Asia/Seoul이다. AI 결과로 대기를 만들지 않는다. 제공된 context 위치/매장만 신뢰하고 AI가 생성한 ID·좌표는 승격하지 않는다. 구체적 시술을 요청한 경우 serviceId 선택을 추가로 요구한다. 지도 API와 장소 지오코딩을 추가하지 않는다. 날짜·가격·반경·좌표 및 리소스 존재 여부는 서버에서 검증한다.

정상적인 비예약 요청은 UNSUPPORTED, 미해결 조건은 NEEDS_CLARIFICATION이다. 모델 거절·잘못된 구조·불완전한 응답·시간 초과·HTTP 장애는 503이며 수동 등록은 AI와 독립적으로 동작한다. connect timeout 3초, 요청 timeout 10초다.

임베딩 기본값은 text-embedding-3-small/1536차원이며 `POST /v1/embeddings`에 `encoding_format=float`을 명시한다. 사용자 입력은 자연어를 포함한 자유로운 JSON 전체다. 카테고리/키워드 DTO로 변환하거나 필드를 추출하지 않고, ACTIVE 대기 조건도 덧붙이지 않는다. user가 원본을 소유하고 OpenAI 호출과 벡터 생성/저장/활용은 search가 맡는다. 예: `{"preference":"네일에 관심이 있고 경력 3년 이상의 시술자가 있는 조용한 숍을 원해요","extra":{"memo":["심플한 디자인"]}}`.

키/중첩 구조는 제한하지 않지만 유효한 JSON이어야 한다. 모델 입력 상한 내에서 보수적으로 처리하기 위해 UTF-8 8,000바이트까지 허용하며, 초과 시 잘라 보내지 않고 PREFERENCES_INPUT_TOO_LARGE로 실패한다. null/공백, JSON null, 빈 객체/배열/문자열은 호출하지 않고 기존 벡터를 제거한다. JSON의 공백/키 순서도 원문대로 전송하므로 문자가 달라지면 다른 입력 해시다.

`search_user_vector`에는 모델·차원·입력 해시·선호 revision을 기록한다. 입력 형식 식별자 `preferences-json-v1`와 모델/차원을 해시에 포함해 이전 방식의 벡터를 재사용하지 않는다. 모델을 변경할 때는 사용자·매장 벡터를 함께 재생성하고 검색 인덱스도 재구축한다. 동일 입력/모델/차원의 정상 벡터는 API를 다시 호출하지 않는다. 저장 직전에 원문 해시와 revision을 재확인해 오래된 응답을 버리고 최신 작업을 등록한다. 검색 시에도 원문 해시/revision을 확인하므로 직접 SQL로 원문을 변경한 경우에도 오래된 벡터를 사용하지 않는다. 대기 변경 작업에서 VECTOR가 등록되어도 입력이 같으면 저장된 벡터를 재사용한다.

`search_user_vector`의 사용자별 30초 lease로 여러 인스턴스의 중복 호출을 제어한다. 외부 호출 동안 DB 트랜잭션/행 잠금을 유지하지 않는다. 이미 생성 중이면 작업을 대기시키며 외부 재시도 횟수를 차감하지 않는다. 중단된 lease는 만료 후 회수하고, lease owner가 바뀐 이전 worker는 저장하거나 새 lease를 지울 수 없다. 정상 응답은 모델명·단일 결과 index·1,536개 숫자·유한값·0이 아닌 norm을 검증한다.

API 키 누락/401/403/할당량 소진/잘못된 요청·응답은 재시도 없이 FAILED, 일반 429/408/409/5xx/연결 실패는 기존 제한된 재시도로 처리한다. 원문, 키, Authorization 헤더, 응답 벡터는 로그에 기록하지 않는다. HTTP 503의 retryable도 오류 종류에 따라 다르다. 인증/할당량 실패 후 키나 계정 상태를 고치면 새 생성 작업을 등록한다.

매장 벡터는 실제 매장/시술 설명으로 생성하며 슬롯 시각·가격만 바뀐 경우 다시 생성하지 않는다. SLOT 매칭 작업에서는 OpenAI를 호출하지 않는다. 인덱스에 벡터가 없는 매장도 필수 조건 검색과 매칭에서 제외하지 않는다. 자연어의 '경력 3년 이상' 같은 선호는 유사도에 느슨하게 반영하며 정형 필수 필터로 자동 승격하지 않는다.

참고: [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Embeddings](https://developers.openai.com/api/docs/guides/embeddings), [Elasticsearch PIT/search_after](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results).

## 설치와 설정

1. 신규 Oracle 대상 스키마에 [search-oracle.sql](sql/search-oracle.sql)을 1회 적용한다. 기존 v1의 11개 search 테이블이 있는 스키마는 [전환 절차](search-schema-v2.md)에 따라 migration 04·05와 v2 코드를 함께 적용한다. 운영 데이터에 테스트 초기화 SQL을 실행하지 않는다. ddl-auto=validate를 유지하며 search는 JDBC를 쓰므로 SearchSchemaVerifier도 필수 테이블/열과 잠금 행을 검사한다.
2. `docker compose -f compose.search.yaml up -d`로 로컬 Elasticsearch 8.19.6을 실행한다. 9200은 localhost에만 바인딩한다. 운영은 인증된 별도 Elasticsearch를 사용한다.
3. 아래 속성을 환경별 Spring 설정에 제공하고 상대 도메인 계약 구현 Bean을 연결한다. 비밀은 저장소에 기록하지 않는다.

| 설정 | 기본값/의미 |
| --- | --- |
| search.elasticsearch.url | http://localhost:9200 |
| search.elasticsearch.alias | bintime-stores |
| search.elasticsearch.authorization | 운영 Authorization 헤더 전체 값 |
| search.internal-token | 미설정 시 S-08 HTTP 전부 거절. X-Internal-Token과 상수 시간 비교 |
| search.cursor-secret | 32자 이상. 미설정 시 프로세스 임시 키이므로 재시작/다중 인스턴스 간 커서 공유 불가 |
| OPENAI_API_KEY | 미설정 시 AI 작업 503, 조건 관리/매칭은 독립 |
| search.ai.model | gpt-4.1-mini, Responses 구조화 출력 모델 |
| search.ai.embedding-model | text-embedding-3-small, 1536차원 고정 |
| search.scheduling-enabled | true, 테스트/문서 확인 시 false 가능 |
| search.worker-delay-ms | 5000 |
| search.expiry-delay-ms | 60000 |

내부 HTTP도 TLS와 인프라 접근 제한을 적용한다. 단일 앱의 store는 HTTP 토큰 대신 공개 MatchService 메서드를 호출한다. 외부 HTTP 요청의 user ID 헤더를 신뢰하지 않는다.

## 복구와 운영

대기 변경/현재 동의/후속 작업은 같은 Oracle 트랜잭션이다. 작업은 QUEUED → RUNNING → SUCCEEDED/FAILED다. DB 조건부 갱신으로 한 worker만 claim하고 15분 lease를 순회 중 갱신한다. 프로세스 중단 시 lease 종료 후 회수한다. 임시 실패는 2/4/8/16초 후 재시도하며 최대 5회다. OpenAI의 영구 오류는 즉시 FAILED로 전환하고, 동일 사용자 생성 대기는 외부 재시도 한도에서 제외한다. 외부 전달의 중복은 N-06이 제거한다.

다음 SQL로 실패/재시도/지연을 확인한다. available_at은 epoch milliseconds이며 QUEUED의 실행 예정 시각, RUNNING의 lease 종료 시각이다. 로그는 작업 ID·종류·시도 횟수·실패 클래스만 남기고 AI 원문과 비밀을 기록하지 않는다.

```sql
select kind, status, count(*) from search_work group by kind, status;
select id, kind, attempts, available_at, last_error from search_work
 where status in ('FAILED','QUEUED','RUNNING') order by available_at;
select event_id, job_id, slot_id, status from search_work
 where kind='SLOT' and status <> 'SUCCEEDED';
select store_id, source_version, model, dimensions from search_store_vector;
```

의존 장애 원인을 고친 후 지정 작업만 `update search_work set status='QUEUED', attempts=0, available_at=<현재 epoch ms> where id='<실패 작업 ID>' and status='FAILED'`로 재처리한다. SLOT 작업은 같은 eventId/jobId를 유지한다. 활성 조건·버전·동의·슬롯은 다시 검사한다. 보관 기간이 끝난 search_idempotency, 종료된 작업/이전 제안은 운영 보관 정책을 정한 뒤 삭제한다. 자동 보관 삭제 정책은 이번 구현에서 임의로 정하지 않았다.

인덱스 초기 생성/재구축은 운영 모듈에서 `SearchIndexService.rebuild()`를 호출한다. 새 버전 인덱스를 만들고 store export를 끝까지 읽은 후 alias를 원자적으로 전환한다. 재구축 중 실패하면 기존 alias를 유지한다. 재구축 중 store 변경 생산자의 재전송을 유지하고, 전환 후 해당 변경을 다시 전달해 최신 버전을 보장해야 한다. 미사용 인덱스 삭제는 운영자가 판단한다. 증분 변경은 changed(전체문서)를 재전송할 수 있으며 ES의 문서 버전으로 오래된 문서가 최신 검색 결과를 덮어쓰지 못하게 한다. 작업 payload에는 전체 문서를 저장하며 매장 벡터 저장 전에는 store export 공개 계약으로 현재 설명을 다시 확인한다. v1 큐에 남은 매장 ID도 같은 공개 조회로 복구한다. 인덱스 변경과 알림은 예약 트랜잭션 밖의 작업이다.

## 검증

기본: `./gradlew format`, `./gradlew test`, `./gradlew build`. H2 테스트는 동의·필수 nullable 키·경계·동시 중복·20개 제한·버전/소유권·멱등·만료·7명 전원/다중 대기 통합·취소 후 발송 차단·작업 회수·AI 실패·서명 커서와 8개 HTTP 경로를 검증한다. OpenAI 외부 유료 호출은 기본 검증에서 하지 않는다.

별도 통합 테스트:

```bash
SEARCH_ELASTICSEARCH_URL=http://localhost:9200 ./gradlew test --tests '*SearchElasticsearchTests'
# SEARCH_ORACLE_*는 비어 있는 전용 테스트 스키마만 사용한다. 이 테스트는 DDL을 적용한다.
SEARCH_ORACLE_URL=jdbc:oracle:thin:@//localhost:1521/FREEPDB1 \
SEARCH_ORACLE_USER=<test-user> SEARCH_ORACLE_PASSWORD=<secret> \
./gradlew test --tests '*SearchOracleTests'
```

실제 Oracle 23 Free 전용 임시 스키마에서 CLOB·잠금·20개 경쟁·멱등·이벤트 제약을, Elasticsearch 8.19.6에서 nested 필터·벡터·PIT·커서·마감 재검증을 검증한다. 상대 도메인 연결 후 사용자 인증부터 실제 알림 수신 및 예약 완료까지는 별도 통합 검증이 필요하다.

## 사용자 벡터 생성 실행

로컬 `.env`에 `OPENAI_API_KEY=<발급한 키>`를 저장한다. 기존 local 프로필이 `.env`를 읽으며 배포는 Compose의 app environment로 키를 전달한다. 비밀을 Git이나 Docker 이미지에 넣지 않는다. 내부 선호 저장 경로는 `UserPreferenceService.replace(userId, json)`를 사용하며, 동기 이벤트 소비자의 작업 저장에 실패하면 선호 변경도 롤백한다. U-08 선호 저장 HTTP API는 구현되어 있지만 이 이벤트 경로는 아직 호출하지 않는다. 별도의 공개 벡터 API는 추가하지 않았다.

이미 저장된 선호의 최초 생성/수동 갱신은 내부 `PersonalizationService.requestRefresh(userId)` 또는 아래 명령으로 작업을 등록한다. 실제 DB에 존재하는 사용자 ID를 사용하며 일반 worker가 후속 처리한다.

```bash
./gradlew bootRun --args='--spring.main.web-application-type=none --bintime.store.expiration-enabled=false --search.vector.rebuild-user=usr_001'
```

확인 SQL (벡터/선호 원문을 출력하지 않는다):

```sql
select user_id, model, dimensions, source_version, input_hash from search_user_vector;
select status, attempts, last_error from search_work where kind='VECTOR';
select user_id, lease_until from search_user_vector where lease_owner is not null;
```

기본 테스트는 실제 OpenAI를 호출하지 않는다. 아래 별도 작업만 `.env` 키로 합성 한국어 선호 JSON 한 건을 OpenAI에 전송한다. 임시 H2에 user 조회 → 이벤트/outbox → worker → 실제 API → 1,536차원 검증/저장을 실행하며 앱 Oracle 데이터는 변경하지 않는다. 키와 벡터를 출력하지 않는다.

```bash
./gradlew testOpenAi
```

`UserVectorFlowTests`는 자유로운 중첩 JSON, 동일 입력 재사용, 저장/outbox 롤백, 생성 중 변경, 동시 호출 방지, 중단 lease 회수, 직접 DB 변경 감지, 빈 선호 제거, 크기 제한, 영구/일시 오류를 검증한다. 동일 테스트를 실제 Oracle의 별도 스키마에서도 실행할 수 있다. 빈 전용 스키마에 초기 전체 DDL과 search DDL을 적용한 뒤 USER_VECTOR_ORACLE_URL/USER_VECTOR_ORACLE_USER/USER_VECTOR_ORACLE_PASSWORD 환경 변수를 설정하고 `./gradlew test --tests '*UserVectorFlowTests'`를 실행한다. 이 테스트는 해당 스키마의 사용자/벡터/작업 데이터를 정리하므로 앱 스키마를 사용하지 않는다.

2026-10-16 검증: `.env`의 실제 키로 합성 한국어 선호 JSON을 임베딩하고, 별도 Oracle 스키마의 당시 `search_vector`(v1) CLOB에 실제 응답을 저장·조회했다. 동일 Oracle 스키마에서 선호 저장/outbox 롤백·동시 호출 방지·lease 회수·오래된 응답 차단 테스트도 통과했으며 임시 스키마는 정리했다. 이 검증에서는 기존 앱 스키마를 변경하지 않았다. 이후 같은 날 로컬 `FREEPDB1/BINTIME_APP`에 전체 search DDL을 별도로 적용했다. 기존 13개 테이블의 행 수를 유지하면서 search 테이블 11개와 잠금 버킷 256개를 추가했으며, 상세 반영 범위는 [진행 문서](search-progress.md#4-영속화와-db-반영)에 기록했다.

2026-10-16 v2 전환: 로컬 `FREEPDB1/BINTIME_APP`에 migration 04·05를 적용해 search를 7개로 단순화했다. 기존 13개 테이블과 행 수, 잠금 256행을 유지했으며 최종 테이블은 20개다. Java/JDBC·worker·테스트도 v2로 전환했고 전체 format/test/build와 격리 Oracle의 대기·벡터 테스트를 통과했다.

## Docker 실행과 운영 ES 연결

로컬은 `docker compose -f compose.search.yaml up -d --wait`로 실행한다. `http://localhost:9200`을 사용하며 인증 없이 localhost에서만 접근한다. 데이터 볼륨은 기존 `search-es`를 유지한다.

운영은 `compose.yaml`의 `elasticsearch` 서비스를 사용한다. 앱과 ES는 같은 서버의 비공개 `search` Docker 네트워크에 연결되고, ES의 9200 포트는 호스트에 공개하지 않는다. `application-prod.properties`의 기본 URL은 `http://elasticsearch:9200`이다. HTTP는 이 단일 호스트 내부 네트워크에 한정한다. 다른 서버의 ES를 사용하면 해당 환경의 신뢰 가능한 HTTPS URL과 인증 정보를 설정한다.

서버 `.env`에 [.env.prod.example](../.env.prod.example)을 참고해 `ELASTIC_PASSWORD`를 추가한다. 예를 들어 `openssl rand -hex 32`로 별도 비밀번호를 생성한다. 기존 데이터 볼륨의 초기 비밀번호를 설정하는 값이므로, 이미 초기화한 ES의 비밀번호 변경은 ES 계정 변경 절차로 수행하고 앱 설정도 함께 갱신한다.

```bash
# 운영 서버에서 실행. 로컬 개발 .env를 그대로 사용하지 않는다.
docker compose -f compose.yaml up -d --wait elasticsearch
# 기존 배포 절차에 따라 새 JAR/이미지를 준비한 후 앱도 배포한다.
docker compose -f compose.yaml up -d --build app nginx
```

| 환경변수 | 의미 |
| --- | --- |
| `ELASTIC_PASSWORD` | 운영 Compose ES의 초기 elastic 비밀번호이자 앱에 전달하는 기본 접속 비밀번호. 필수 |
| `SEARCH_ELASTICSEARCH_URL` | 기본 `http://elasticsearch:9200` |
| `SEARCH_ELASTICSEARCH_ALIAS` | 기본 `bintime-stores` |
| `SEARCH_ELASTICSEARCH_USERNAME` | 기본 `elastic` |
| `SEARCH_ELASTICSEARCH_AUTHORIZATION` | 선택적 전체 Authorization 헤더. 설정하면 Basic 계정보다 우선. 별도 계정의 ApiKey도 가능 |
| `ES_JAVA_OPTS` | 기본 `-Xms512m -Xmx512m` |
| `ES_MEMORY_LIMIT` | ES 컨테이너 메모리 상한, 기본 `1g` |

Compose는 `ELASTIC_PASSWORD`를 앱의 `SEARCH_ELASTICSEARCH_PASSWORD`로 전달한다. Compose 밖에서 JAR를 실행하거나 별도 ES 계정을 사용하면 `SEARCH_ELASTICSEARCH_USERNAME/PASSWORD`를 프로세스 환경변수로 제공한다. 별도 서버 설정에 맞춰 Compose의 앱 비밀번호 매핑을 바꾸거나 Authorization 헤더를 사용한다. 자체 서명 인증서는 JVM 신뢰 저장소 설정이 별도로 필요하며 인증서 검증을 해제하지 않는다.

운영은 ES 영속 볼륨·재시작 정책·인증된 상태 확인을 사용하며 ES healthy 이후 앱을 시작한다. 초기 `elastic` 계정은 설정 단순화를 위한 기본값이며, 운영 계정 정책에 따라 필요한 인덱스/alias/PIT 권한의 전용 계정이나 API 키로 전환할 수 있다. ES 프로세스가 정상이어도 인덱스 생성·store 데이터 연결은 별도다. 초기 데이터 공급은 `SearchIndexService.rebuild()`와 store 공개 계약 연결이 필요하다.

설정 참고: [Elastic Docker 설정](https://www.elastic.co/docs/deploy-manage/deploy/self-managed/install-elasticsearch-docker-configure), [보안 최소 설정](https://www.elastic.co/guide/en/elasticsearch/reference/current/security-minimal-setup.html).

## Store 카탈로그 연결 및 초기 색인

`StoreSearchCatalogService`의 실제 Bean을 사용한다. store의 Repository를 search에서 직접 조회하지 않는다. 로컬 `BINTIME_APP`에는 2026-10-16 migration 06을 적용해 `STORE_SHOPS.SEARCH_VERSION`을 추가했다. 기동 시 현재 store 이벤트 엔티티가 요구하는 migration 07도 추가 적용했으며, 두 작업에서 기존 20개 테이블의 행 수를 보존했다. 운영 DB에는 자동 적용하지 않는다.

초기 인덱스/alias가 없거나 전체 재구축이 필요할 때 운영자가 명시적으로 실행한다. DB 스키마 검증 후 현재 store export 전체를 읽고 새 인덱스를 만든 다음 alias를 전환한다. 별도 공개 관리 API는 추가하지 않았다.

```bash
./gradlew bootRun --args='--search.index.rebuild=true --search.scheduling-enabled=false --bintime.store.expiration-enabled=false --bintime.store.event-delivery-enabled=false'
```

위 명령은 서버를 계속 실행한다. 초기 색인 점검 중 자동 작업을 멈춘 예시이며, 점검 후 플래그를 제거한 정상 설정으로 재시작해야 벡터·매칭·이벤트 worker가 처리된다. 초기 문서는 캐시 벡터 없이도 검색 가능하다. 새 매장 임베딩은 STORE_VECTOR 후속 작업이 처리한다. 매번 기동할 때 자동 재구축하지 않도록 기본값은 비활성이다.

`StoreCatalogConnectionTests`는 실제 store 서비스를 통해 등록한 매장·시술·슬롯으로 대기 등록/상세·초기 색인·거리/가격/익명 추천 검색·예약 완료·마감 슬롯 재검증을 확인한다. 기본은 H2와 검색 엔진 대역이며 아래 설정에서는 실제 ES를 사용한다. OpenAI는 이 테스트에서 대역을 사용한다.

```bash
SEARCH_ELASTICSEARCH_URL=http://localhost:9200 ./gradlew test --tests '*StoreCatalogConnectionTests'
```

실제 Oracle도 연결하려면 빈 전용 테스트 스키마에 최신 전체 초기 DDL과 search DDL을 적용하고 `CATALOG_ORACLE_URL/USER/PASSWORD`를 제공한다. USER는 안전장치로 `BT_CATALOG_` + 대문자 16진수 12자리여야 한다. 테스트는 업무 행을 생성하므로 앱 스키마를 사용하지 않는다. 테스트용 ES alias/index는 UUID로 분리하고 종료 시 정리한다.

검증 기록: 격리 Oracle + 실제 ES에서 위 흐름을 통과했고, 로컬 앱 DB에서도 서버를 기동해 초기 `bintime-stores` alias 생성과 S-01 HTTP 200을 확인했다. 로컬에는 매장/슬롯이 0건이라 응답은 빈 목록이며 테스트용 업무 데이터를 앱 DB에 추가하지 않았다. 점검 서버는 종료했고 ES 컨테이너와 초기 인덱스는 유지했다.

알림 연결의 실제 Bean·로컬 DDL·Oracle 통합 검증·남은 실기기 QA는 [Search → Notification 연결](search-notification-integration.md)을 따른다. 정상 프로필에서 알림 대체 Bean을 사용하지 않으며 `NotificationSchemaVerifier`가 누락된 알림 JDBC 열을 기동 시 검증한다. 로컬 알림 확장 후 전체 테이블은 25개이며 search 7개 구성은 유지된다.
