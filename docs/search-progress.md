# Search 구현 현황과 후속 작업

기준일: 2026-10-16. DB v2 전환, Docker/운영 ES 설정, 실제 store 카탈로그 연결과 초기 색인까지 반영한 기록이다. 저장소의 실제 코드, 테스트 결과와 로컬 실행 결과를 기준으로 작성했다. 우선순위는 다음 구현을 위한 제안이며, 상대 도메인의 미연결 계약을 완료로 계산하지 않는다.

이 문서는 구현 범위와 진행 상황을 관리한다. 세부 정책·설정·실행 명령·복구 SQL은 [Search 구현 및 운영](search.md), store 측 진행 상황은 [Store 구현 현황](store-progress.md)을 참고한다.

## 최근 작업 완료 내역

| 작업 | 완료한 내용 | 실행·배포 범위 |
| --- | --- | --- |
| 사용자 임베딩 | 자유로운 선호 JSON 원문을 OpenAI로 전송, 입력 해시/모델/차원 기반 캐시, 변경 이벤트/outbox, 동시 생성 lease·오래된 응답 차단 | 내부 서비스 구현. 실제 키의 임베딩/Oracle 저장은 v1에서 검증했고, v2는 격리 Oracle의 흐름 테스트 통과 |
| DB 단순화 | 11개 → 7개 search 테이블, 현재 동의만 저장, 사용자 벡터 상태 통합, 매장 벡터만 캐시, 매칭 이벤트를 work로 통합 | 로컬 `FREEPDB1/BINTIME_APP` 전환 완료. 전체 24개 → **20개**, 기존 13개 테이블 행 수와 잠금 256행 유지 |
| DDL·코드 전환 | 신규 설치 DDL, 기존 데이터 전환 준비/마무리 SQL, JDBC·worker·시작 검증·테스트 수정 | [v2 구성·적용 기록](search-schema-v2.md). 다른 환경에는 별도 적용 필요 |
| 로컬 Elasticsearch | 기존 데이터 볼륨을 유지해 Compose 실행, 상태 확인·재시작 정책 추가 | `server-elasticsearch-1`, `http://localhost:9200`, healthy / green 확인 |
| 운영 Elasticsearch | 운영 Compose에 ES·영속 볼륨·비공개 네트워크·비밀번호 인증·health 의존성 추가 | 임시 Compose 프로젝트에서 실행 검증. **실제 운영 서버 배포는 미수행** |
| 운영 앱 접속 | prod 기본 주소 `http://elasticsearch:9200`, URL/alias/계정/비밀번호/Authorization 환경변수, Basic 인증과 명시적 헤더 우선 처리 | `application-prod.properties`, `.env.prod.example`, 접속 Adapter와 인증 테스트 반영 |
| 회귀 검증 | `./gradlew format test build --console=plain` 성공 | 운영 ES 설정 단계는 149건 중 실패/오류 0·외부 검증 3건 skip. 이후 카탈로그 연결에서도 전체 테스트·빌드를 통과했고 Oracle+ES는 별도 검증 |

대기·자동 알림, Oracle 벡터 캐시, 현재 조건의 동의는 유지한다. ES 프로세스 실행과 접속 설정 완료는 실제 매장 데이터의 색인·검색·알림 연결 완료를 의미하지 않는다. 현재 데이터 공급과 도메인 연결이 다음 구현 단계다.

### Store 연결 후속 반영

- 실제 카탈로그 Bean 주입과 API-docs 전용 대체 Bean을 분리했다.
- 로컬 DB에 06(검색 버전)과 현재 엔티티 기동에 필요한 07(이벤트 전달 메타데이터)을 추가 적용했다. 07의 신규 컬럼 백필 SQL을 실행 시점에 파싱하도록 수정하고 재실행을 확인했다.
- 초기 색인 실행 옵션과 alias별 고유 인덱스 이름을 추가했다. 로컬 서버 기동·초기 alias 생성·S-01 HTTP 200을 확인했다. 앱 DB의 매장·슬롯은 0건이므로 로컬 응답은 빈 목록이다.
- `StoreCatalogConnectionTests`를 추가했다. 격리 Oracle/실제 ES에서 등록 → 대기 → 색인/벡터 캐시 → HTTP 검색 → 예약 → 오래된 ES 슬롯 제외 → FULFILLED를 확인했다. OpenAI는 대역이며 실제 발송은 범위 밖이다.

## 1. 현재 상태

**S-01~08의 Controller·Service·Repository와 OpenAI/Elasticsearch Adapter는 구현되어 있다. 사용자 선호 JSON으로 실제 OpenAI 임베딩을 생성하고 Oracle에 저장하는 경로도 검증했다. 실제 사용자 인증부터 매장 검색·매칭·알림 수신까지 이어지는 통합 흐름은 아직 미완료다.**

| 영역 | 구현 상태 | 남은 연결 |
| --- | --- | --- |
| 예약 대기 관리 | 실제 store 검증/표시 조회 연결, 조건·동의·버전·멱등성·만료·영속 작업 구현 | 인증/알림 설정과 전체 사용자 흐름 QA |
| 조건 검색 | 실제 store 카탈로그 연결·초기 색인·S-01/예약 후 재검증 통합 확인 | 증분 이벤트의 운영 연결·복구 QA |
| 자연어 분석 | Responses 구조화 출력·서버 검증·추가 질문·장애 구분 구현 | 실제 사용자 요청 연결 및 자연어 품질 QA |
| 사용자 벡터 | 자유로운 JSON 조회 → 작업 저장 → OpenAI → Oracle 캐시 구현 | 구현된 U-08 저장 경로와 변경 이벤트 연결 |
| 매칭 | 모든 적격 고객 순회·제안 저장·고객별 통합·발송 전 재검증 구현 | store 이벤트 전달과 notification 접수 |
| 작업 복구 | DB claim·lease·재시도·중단 작업 회수 구현 | 운영 지표·보관 정책·장애 대응 검증 |
| 예약과 대기 완료 | store 예약 트랜잭션에서 search 협력 서비스 호출 구현 | 실제 검색·알림을 거친 예약 전체 QA |
| Oracle 스키마 | 로컬 v2 전환 완료, search 7개·전체 20개 | 다른 환경별 DDL 적용·확인 |
| Elasticsearch 인프라 | 로컬 실행·초기 alias 구축, 운영 Compose·prod 접속/인증 설정 구현 | 실제 운영 배포·실제 운영 데이터 초기 색인 |

정상 프로필의 `SearchStoreCatalog`는 `StoreSearchCatalogService`다. 503 대체 Bean은 api-docs에만 남겼고 실제 Bean 1개가 주입되는 것을 검증했다. user의 인증 Adapter와 store 이벤트 consumer도 현재 별도 작업에서 추가되고 있으며 [search 인증](search-auth.md), [이벤트 전달](store-event-delivery.md)에 계약이 있다. 이번 연결 검증에서 `notificationsEnabled`와 `SearchNotificationGateway`의 실제 알림 연동은 완료로 계산하지 않는다. 테스트 대역의 OpenAI/알림 결과와 실제 전달을 구분한다.

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

빈 선호는 캐시 벡터만 비우고 revision·lease를 관리하는 사용자 상태 행은 유지한다. UTF-8 8,000바이트 초과 입력은 자르지 않고 거절한다. 사용자별 30초 생성 lease로 동시 호출을 제어하며, 외부 API 호출 동안 DB 잠금을 유지하지 않는다. 영구 오류와 일시 오류의 재시도를 구분한다. 키·선호 원문·벡터는 로그에 출력하지 않는다.

**U-08 선호 저장 HTTP API는 구현되어 있다.** 현재 `UserProfileService.savePreferences`는 `UserProfileRepository.updatePreferences`로 저장하며 `UserPreferencesChanged` 이벤트를 발행하지 않는다. 따라서 HTTP 저장 이후 VECTOR 작업이 자동 생성되는 연결은 남아 있다. 기존 `text` 자유 입력과 expectedVersion 계약을 보존하면서 내부 저장 경로와 이벤트 처리를 정합화해야 한다. 임베딩 입력은 저장된 JSON 전체이며 특정 필드만 추출하지 않는다. 최초 생성·수동 재생성 명령은 [운영 문서](search.md#사용자-벡터-생성-실행)에 있다.

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

신규 환경은 [search-oracle.sql](sql/search-oracle.sql)을 사용한다. 기존 v1의 11개 테이블은 [전환 절차](search-schema-v2.md)에 따라 데이터 복사 후 7개로 전환한다. 기존 `search_waitlists`는 별도로 유지하며 예약 협력 서비스가 지원한다. 2026-10-16 로컬 앱 DB에 migration 04·05 적용을 완료했고, 최종 앱 테이블은 기존 13개 + search 7개 = 20개다. 업무용 테스트 데이터는 이번 전환에서 추가하지 않는다.

## 5. 검증 현황

검증 시점과 범위를 구분한다. 아래는 기존 구현과 최근 코드 전환 단계의 결과이며, 이번 진행 문서 정리에서는 테스트나 유료 API 호출을 다시 실행하지 않았다.

| 검증 | 확인된 결과 | 범위·제한 |
| --- | --- | --- |
| `./gradlew format test build --console=plain` | v2 및 운영 ES 인증 설정 반영 후 성공 | 운영 ES 설정 단계 149건, 실패/오류 0·외부 검증 3건 skip. 카탈로그 연결 후에도 전체 검증 재실행 성공 |
| `SearchBehaviorTests` | 서비스·HTTP 동작 검증 | 동의/시간/가격/소유권/중복/20개 경쟁/멱등/만료/7명 전원/다중 대기 통합/취소 후 발송 차단 등, 도메인 연동은 테스트 대역 |
| `OpenAiIntentAdapterTests` | 외부 프로토콜·오류 검증 | 구조화 출력·임베딩 응답·키 누락·시간 초과, 실제 자연어 품질 평가는 아님 |
| `UserVectorFlowTests` | H2 및 격리 Oracle에서 검증 | JSON 원문·재사용·롤백·동시 생성·lease 회수·오래된 응답 차단·오류 분류 |
| `SearchOracleTests` | 별도 Oracle 23 Free 스키마에서 검증 | CLOB·잠금·20개 경쟁·멱등·이벤트 제약, 기본 실행에서는 skip |
| `SearchElasticsearchTests` | 실제 Elasticsearch 8.19.6에서 검증 | nested 조건·가격/벡터 정렬·PIT/커서·마감 슬롯 재검증, 기본 실행에서는 skip |
| `testOpenAi` | v1 당시 실제 키로 합성 선호 JSON 임베딩 성공 | H2 및 별도 Oracle에서 실제 저장 확인. v2 전환/운영 ES 설정 단계에서는 유료 호출 재실행 안 함 |
| v2 로컬 DB 전환 | 24개 → 20개, search 7개·잠금 256행, 기존 13개 테이블 행 수 보존 | 코드 전환 후 format/test/build 및 격리 Oracle 대기·벡터 테스트 통과 |
| `StoreCatalogConnectionTests` | 기본 H2/검색 대역과 격리 Oracle/실제 ES에서 성공 | 실제 store Bean·대기·초기 색인·S-01·예약 후 제외, 테스트 인덱스/스키마 정리 |
| `ElasticsearchAuthenticationTests` | HTTP 요청의 인증 헤더 검증 | Basic 계정, 명시적 ApiKey 헤더 우선, 익명 접속 확인 |
| 운영 Compose 정적 확인 | ES 외부 포트 없음·internal 네트워크·앱 URL·healthy 의존성 확인 | 비밀값을 출력하지 않고 설정 검증 |
| 운영 ES 실제 실행 | 임시 프로젝트에서 healthy, 비인증 401, 인증 후 health 성공 | 검증용 컨테이너/네트워크/볼륨 정리. 운영 서버 배포와 구분 |

아직 실제 로그인 → 선호 저장 → 대기 등록 → 매장 빈자리 발행 → 전원 매칭 → 실제 알림 → 예약 확정까지의 통합 검증은 하지 않았다. 실제 Responses API의 한국어 분석 품질, 다중 인스턴스 강제 종료 복구, 운영 부하도 별도 검증 대상이다.

## 6. 다음 구현 우선순위

**카탈로그 연결과 초기 색인은 완료했다. 다음 단계는 store 이벤트 consumer가 실제 발행·예약·철회 후 ES와 매칭 작업을 갱신하는지 검증하는 것이다.** 현재 추가된 consumer의 재시도·재시작과 user 인증 연결을 함께 점검한 후 실제 notification을 연결한다.

| 순서 | 다음 작업 | 확인할 결과 |
| --- | --- | --- |
| 1 · 완료 | 실제 store 조회 5개 연결, `rebuild()` 및 S-01 통합 검증 | 격리 Oracle+실제 ES에서 거리/가격/익명 추천·예약 마감 제외 확인 |
| 2 | store 이벤트 consumer와 증분 색인·매칭 연결 | 발행·예약·철회·변경이 재시작 후에도 반영되고 오래된 이벤트가 최신 문서를 덮어쓰지 않음 |
| 3 | 기존 user 세션/CSRF와 search 연결, U-08 변경 이벤트 연결 | 로그인 고객이 대기를 관리하고 선호 저장 시 벡터 작업이 자동 등록됨 |
| 4 | N-06 접수와 실제 알림 전달 | 적격 고객 전원 처리, user+slot 중복 방지, 취소/변경 후 발송 차단 |
| 5 | 실제 로그인 → 선호 → 대기 → 발행 → 알림 → 예약 통합 QA | 예약 완료·대기 종료·슬롯 마감까지 일관되게 확인 |

운영 배포 준비는 위 코드 연결과 병행할 수 있다. 서버 `.env`에 `ELASTIC_PASSWORD`를 설정하고, v2 DDL을 적용한 뒤 새 이미지와 ES를 배포해야 한다. 실제 운영 환경의 인증 접속과 health, 인덱스 초기 구축은 배포 후 따로 확인한다. 설정·명령은 [Docker 실행과 운영 ES 연결](search.md#docker-실행과-운영-es-연결)을 따른다.

### P0-1. 실제 store 조회 계약 연결 — 완료

`StoreSearchCatalogService`의 `validate`, `available`, `revalidate`, `describe`, `export` 구현을 search에 연결했다. 초기 색인은 `search.index.rebuild=true`로 실행할 수 있다. 현재 매장 벡터 저장 전 최신 문서 확인과 v1 잔여 큐 복구는 export를 순회하므로, 우선 정확한 전체 순회를 연결하고 필요하면 store ID별 공개 조회를 추가한다. search에서 store Repository를 직접 호출하지 않는다. 전체/증분 문서의 버전은 매장·시술·슬롯 중 검색 결과에 영향을 주는 모든 변경에서 증가하도록 정한다.

완료 기준: 실제 Oracle 매장·시술·슬롯으로 `SearchIndexService.rebuild()` 후 S-01 검색, 대기 조건의 store 검증, 기존 슬롯 탐색, 마감 슬롯 제외가 동작한다. 사용자 벡터가 없는 경우에도 거리/가격 검색을 확인하고, 매장 벡터가 생성되면 같은 설명은 캐시를 재사용하는지 확인한다. export/available의 마지막 커서까지 누락 없이 읽으며, 특정 매장·시술 불일치를 거절한다.

### P0-2. store 이벤트 전달과 검색 인덱스 동기화

store가 저장하는 이벤트를 durable consumer로 전달해 `SearchIndexService.changed`와 `MatchService.submit`을 호출한다. 발행·철회·예약·만료·매장/시술 변경을 반영하고 삭제/비공개는 tombstone으로 처리한다. 초기 재구축은 `rebuild()`를 사용하되 재구축 중 변경을 놓치지 않도록 재전송·진행 위치·재시도 경계를 연결한다.

완료 기준: 중복·역순 이벤트와 consumer 재시작에도 최신 검색 문서가 유지된다. 발행 이벤트 한 건은 같은 jobId를 재사용하고, 예약·철회된 슬롯은 검색과 알림 후보에서 빠진다. 인덱스 재구축 중 변경도 전환 후 반영된다.

### P0-3. user 인증·알림 설정·선호 저장 API

이미 구현된 카카오 세션 인증/CUSTOMER 역할/CSRF를 `SearchUserAccess.customer`에 연결하고, `notificationsEnabled`의 원본 소유 도메인과 실제 조회를 연결한다. 구현된 U-08의 `text`와 expectedVersion 계약을 보존하면서 선호 저장과 변경 이벤트를 같은 트랜잭션에서 처리한다. 기존 저장 후 같은 값을 `replace`에 다시 넘기면 동일 입력으로 이벤트가 생략될 수 있으므로, 저장 경로를 정합화하거나 이벤트 처리를 공통화한다. 공개 검색의 인증 사용자 식별도 함께 확인한다.

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
| ES 실행·운영 연결 | [로컬 Compose](../compose.search.yaml), [운영 Compose](../compose.yaml), [prod 설정](../src/main/resources/application-prod.properties), [환경변수 예시](../.env.prod.example) |
| 예약 완료 협력 | [WaitlistReservationService](../src/main/java/com/bintime/search/service/WaitlistReservationService.java) |
