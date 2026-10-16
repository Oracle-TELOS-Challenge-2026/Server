# Search 구현 및 운영

## 범위와 의존 작업

S-01~08은 MVC Service/JDBC Repository와 OpenAI·Elasticsearch Adapter로 처리한다. Oracle이 원본이며 Elasticsearch는 파생 인덱스다. 인증부터 실제 알림 수신까지는 아래 공개 계약의 실제 구현 연결이 필요하다. 기본 구현은 빈 결과나 성공을 위장하지 않고 401/403/503으로 거절한다. 테스트 대역은 `src/test`에만 있다.

- user: `SearchUserAccess.customer`는 검증된 사용자 ID, CUSTOMER 역할과 변경 요청 CSRF를 검증한다. `notificationsEnabled`와 `explicitPreferences`를 제공한다. 선호 변경 커밋 후 `PersonalizationService.preferencesChanged`를 재전송 가능한 방식으로 호출한다.
- store: `SearchStoreCatalog.validate`는 실제 매장·카테고리·시술 관계를 검증한다. `describe`는 표시용 이름을 제공한다. `available`과 `export`는 cursor가 null일 때 첫 페이지이며 nextCursor=null까지 누락 없이 순회한다. `revalidate`는 현재 AVAILABLE이며 미래인 슬롯만 반환한다. 슬롯 start/end와 가격은 실제 원장 값이다. 문서 version은 매장·서비스·슬롯의 모든 검색 관련 변경마다 증가한다.
- store 커밋 후 `MatchService.submit(eventId, slotId, publicationVersion)`와 `SearchIndexService.changed(전체문서)`를 생산자 outbox 등으로 재전달해야 한다. 슬롯 삭제/매장 비공개는 slots=[] tombstone을 전달한다. 예약 완료는 user/intent/version/reservationId를 담아 `WaitlistService.fulfilled`로 전달한다. 검색 도메인이 예약을 확정하지 않는다.
- notification: `SearchNotificationGateway.dispatch`는 N-06 DTO를 접수한다. 동일 user+slot을 중복 발송하지 않고, 최종 동의·발송 제한·대기 ID/버전과 슬롯 유효성을 재확인한다. 취소와 외부 발송 사이의 경쟁은 수신자의 최종 검증 계약으로 닫는다. 검색 DB 잠금을 외부 호출 동안 유지하지 않는다.

기존 다른 작업의 `WaitlistReservationService`/JPA Waitlist는 이 구현의 `search_intent`와 별개다. 해당 예약 연동은 위 공개 완료 계약으로 정합화한 후 연결해야 한다. search는 다른 도메인의 Repository를 호출하지 않는다.

## 확정한 정책과 HTTP 계약 변경

- 필수 조건에 일치하는 고객 전원이 대상이다. top-5/top-k나 유사도 임계값은 없다. 여러 대기가 일치하면 N-06 matches에 ID/버전을 묶는다.
- 시술 시작부터 종료까지 희망 구간에 포함한다. 동일 instant의 서로 다른 offset은 동일하게 판단한다. 거리 계산은 지구 반경 6,371,008.8m의 구면 직선거리다. 가격 경계는 포함하며 원 단위 KRW 정수다.
- 매장/카테고리/시술 ID는 정확히 일치한다. keywords는 필수 필터가 아니다. 특정 매장 이외 대체 매장을 자동 추천하지 않는다.
- **S-03/S-06에 notificationConsent=true가 필수로 추가됐다.** 기존 클라이언트 변경이 필요하다. 조건 전체 교체, 동의 시각·조건 버전을 이력으로 기록한다. WaitlistConditions의 nullable 필드와 S-08 publicationVersion도 키를 반드시 보내야 하며 null과 누락은 다르다.
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

임베딩 기본값은 text-embedding-3-small/1536차원이다. 사용자 벡터는 명시적 선호와 확인된 ACTIVE 조건으로 생성한다. 모델·차원·입력 해시·원본 버전을 저장하고 변경 중 도착한 오래된 응답은 버린다. 매장 벡터는 실제 매장/시술 설명으로 생성하며 슬롯 시각·가격만 바뀐 경우 다시 생성하지 않는다. SLOT 매칭 작업에서는 OpenAI를 호출하지 않는다. 인덱스에 벡터가 없는 매장도 필수 조건 검색과 매칭에서 제외하지 않는다.

참고: [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [Embeddings](https://developers.openai.com/api/docs/guides/embeddings), [Elasticsearch PIT/search_after](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/paginate-search-results).

## 설치와 설정

1. Oracle 대상 스키마에 [search-oracle.sql](sql/search-oracle.sql)을 1회 적용한다. 운영 데이터에 테스트 초기화 SQL을 실행하지 않는다. ddl-auto=validate를 유지하며 search는 JDBC를 쓰므로 SearchSchemaVerifier도 필수 테이블/열과 잠금 행을 검사한다.
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

대기 변경/동의 이력/후속 작업은 같은 Oracle 트랜잭션이다. 작업은 QUEUED → RUNNING → SUCCEEDED/FAILED다. DB 조건부 갱신으로 한 worker만 claim하고 15분 lease를 순회 중 갱신한다. 프로세스 중단 시 lease 종료 후 회수한다. 임시 실패는 2/4/8/16초 후 재시도하며 최대 5회다. 외부 전달의 중복은 N-06이 제거한다.

다음 SQL로 실패/재시도/지연을 확인한다. available_at은 epoch milliseconds이며 QUEUED의 실행 예정 시각, RUNNING의 lease 종료 시각이다. 로그는 작업 ID·종류·시도 횟수·실패 클래스만 남기고 AI 원문과 비밀을 기록하지 않는다.

```sql
select kind, status, count(*) from search_work group by kind, status;
select id, kind, attempts, available_at, last_error from search_work
 where status in ('FAILED','QUEUED','RUNNING') order by available_at;
select event_id, job_id, slot_id, status from search_match_job where status <> 'SUCCEEDED';
select store_id, source_version from search_store_projection
 where vector_hash is null or vector_hash <> input_hash;
```

의존 장애 원인을 고친 후 지정 작업만 `update search_work set status='QUEUED', attempts=0, available_at=<현재 epoch ms> where id='<실패 작업 ID>' and status='FAILED'`로 재처리한다. SLOT 작업은 같은 eventId/jobId를 유지한다. 활성 조건·버전·동의·슬롯은 다시 검사한다. 보관 기간이 끝난 search_idempotency, 종료된 작업/이전 제안은 운영 보관 정책을 정한 뒤 삭제한다. 자동 보관 삭제 정책은 이번 구현에서 임의로 정하지 않았다.

인덱스 초기 생성/재구축은 운영 모듈에서 `SearchIndexService.rebuild()`를 호출한다. 새 버전 인덱스를 만들고 store export를 끝까지 읽은 후 alias를 원자적으로 전환한다. 재구축 중 실패하면 기존 alias를 유지한다. 재구축 중 store 변경 생산자의 재전송을 유지하고, 전환 후 해당 변경을 다시 전달해 최신 버전을 보장해야 한다. 미사용 인덱스 삭제는 운영자가 판단한다. 증분 변경은 changed(전체문서)를 재전송할 수 있으며 오래된 store 버전은 거절한다. 인덱스 변경과 알림은 예약 트랜잭션 밖의 작업이다.

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
