# Search DB 단순화: 7개 테이블

결정일: 2026-10-16. 예약 대기·자동 알림 유지, Oracle 벡터 캐시 유지, 현재 조건의 동의만 저장한다.

## 적용 상태와 파일

- [신규 DDL](sql/search-oracle.sql), [v2 명시 파일](sql/search-oracle-v2.sql): 빈 스키마에 실행한다. 기존 스키마에 전체 실행하지 않는다.
- [기존 v1 전환 준비 SQL](../infra/oracle/migrations/04-search-v2-prepare.sql): 모든 앱 인스턴스를 중지하고 백업 후 1회 실행한다. 새 캐시 복사와 동의·이벤트 메타데이터 이전을 수행한다.
- [v2 전환 마무리 SQL](../infra/oracle/migrations/05-search-v2-finalize.sql): 검증 후 구 테이블 6개를 제거한다. 레거시 `search_waitlists`는 유지한다.
- [기존 v1 DDL](sql/search-oracle-v1.sql): 기존 스키마의 역사적 구성과 복구 참고용이다. 신규 설치에는 사용하지 않는다.

Java/JDBC 코드·시작 검증·H2 테스트 스키마를 v2로 전환했다. 기존 데이터가 있는 다른 환경에서는 백업·앱 쓰기 중지·04/05 적용·v2 코드 배포를 함께 진행한다. Oracle DDL은 암묵적 커밋이 발생하므로 부분 실패 시 전체 파일을 재실행하지 말고 실행된 구문부터 확인한다. v1 앱을 다시 실행하면 제거된 테이블에 접근하므로 정상 동작하지 않는다.

## 최종 테이블 구성

| 테이블 | 보관 데이터 | 유지 이유 |
| --- | --- | --- |
| `search_intent` | 현재 대기 조건·상태·조건 버전·동의 시각/버전·예약 연결 | 자동 알림 대상과 유효 조건의 원본 |
| `search_offer` | 대기 버전별 슬롯 제안 | 대기 조회와 발송 전 버전 확인 |
| `search_idempotency` | 사용자·작업·키별 요청 해시와 최초 응답, 만료 | 등록·취소 재요청 결과 재생 |
| `search_work` | 후속 작업 payload·상태·재시도·lease, 슬롯 이벤트 ID와 공개 jobId | 비동기 처리·재시작 복구·이벤트 중복 방지 |
| `search_user_lock` | 잠금 버킷 256개 | 첫 등록을 포함한 동시 중복·ACTIVE 20개 제한 제어 |
| `search_user_vector` | 현재 선호 revision·벡터 캐시·입력 해시·모델·생성 lease | 사용자 임베딩 재사용과 오래된 응답 차단 |
| `search_store_vector` | 매장 벡터·입력 해시·모델·원본 버전 | ES 재구축 시 임베딩 재사용 |

벡터는 JSON CLOB으로 캐시하며 기본 차원은 1,536이다. 거리·가격·시간·매장·시술 필수 조건과 벡터 유사도 순위는 별도로 처리한다. 적격 고객 전원이 자동 알림 대상이다.

## 기존 11개에서 변경되는 부분

| 기존 | v2 |
| --- | --- |
| `search_consent` | 제거. 현재 동의는 `search_intent.consent_at/consent_version`에 저장 |
| `search_vector` + `search_preference_revision` + `search_vector_generation` | `search_user_vector` 하나로 통합 |
| `search_match_job` | 이벤트 ID·공개 jobId·슬롯·발행 버전을 `search_work`로 통합 |
| `search_store_projection` | 전체 문서 복제를 없애고 `search_store_vector`에 벡터만 캐시 |
| 나머지 대기·제안·멱등·작업·잠금 | 유지. 대기에는 동의 버전, 작업에는 이벤트 식별 열 추가 |

매장·시술·슬롯 원본은 store가 소유한다. 검색용 전체 문서는 ES에 저장한다. 인덱스 초기 생성/복구 시 store 공개 조회에서 최신 문서를 읽고, 설명의 입력 해시·모델·차원이 맞는 Oracle 벡터를 합쳐 ES에 저장한다. 설명만 같고 슬롯 시각·가격만 바뀌었다면 캐시 벡터를 재사용한다. Oracle에 별도 전체 매장 문서를 보관하지 않는다.

## 현재 동의의 의미

- 등록 시 `resource_version=1`, `consent_version=1`, `consent_at=현재 시각`을 함께 저장한다.
- 조건 변경 시 재동의가 필수다. 조건 버전과 동의 버전을 함께 증가시키고 동의 시각을 갱신한다.
- 이전 조건의 동의 이력은 별도 적재하지 않는다.
- 발송 직전 ACTIVE, 미래 시간, 현재 조건 버전=동의 버전=매칭된 대기 버전인지 확인한다.
- 취소·만료·예약 완료 시 상태와 리소스 버전은 바뀔 수 있지만, 동의 버전은 마지막으로 동의했던 조건의 버전으로 남는다. 종료 상태에서는 발송하지 않는다.

v2 신규 DDL은 ACTIVE 행의 동의 시각과 버전 일치를 CHECK 제약으로 보조한다. 데이터베이스가 슬롯 상태·거리 등 모든 발송 조건을 보장하는 것은 아니며 서비스의 최종 검증도 유지한다.

## 통합 후 지켜야 할 규칙

### 사용자 벡터 상태

`revision`은 현재 선호의 버전이고 `source_version`은 캐시 벡터를 만들 때 사용한 선호 버전이다. 선호 저장과 revision 갱신·VECTOR 작업 저장은 같은 트랜잭션이다. 선호가 없어지면 벡터 관련 열을 NULL로 비우고 상태 행은 남긴다. 상태 행을 삭제하면 revision이나 생성 lease가 유실된다.

캐시 열은 모두 채우거나 모두 비운다. 생성 시작 시 lease owner/만료를 함께 설정하고, API 호출은 DB 트랜잭션 밖에서 수행한다. 저장 직전 현재 입력 해시·revision·lease owner를 재확인한다. 다른 worker로 소유권이 넘어간 응답은 버리고, lease 해제도 owner 조건으로 처리한다. 같은 입력 캐시를 재사용할 때는 source_version을 최신 revision에 맞춘다.

### 매칭 작업

`search_work.id`는 내부 실행 작업 ID, `job_id`는 S-08이 반환하는 공개 식별자다. 기존 응답과의 호환을 위해 두 값을 구분했다. SLOT 작업에만 event_id/job_id/slot_id가 필수이며 각각 이벤트·공개 jobId에 유일 제약을 둔다. publication_version은 기존 HTTP 계약대로 null을 허용한다.

동일 eventId·동일 슬롯/발행 버전이면 기존 jobId를 반환하고, 내용이 다르면 409다. 작업 상태는 공통 status로 관리한다. 실패 재시도와 프로세스 종료 후 회수는 유지한다. 이벤트 중복 방지를 유지할 기간에는 완료된 SLOT 작업도 삭제하면 안 된다. 삭제하려면 중복 제거 기록을 보관하는 대안과 보관 기간을 먼저 결정해야 한다.

### 매장 인덱스 작업

기존 INDEX/STORE_VECTOR payload는 store ID이며, 기존 코드는 Oracle projection에서 문서를 읽는다. v2에서는 store ID로 최신 전체 문서를 읽는 공개 계약을 연결하거나 작업 payload에 커밋된 문서를 포함해야 한다. ES 문서의 원본 버전 비교·역순 이벤트 거절도 유지한다. 이 경로를 연결하기 전에 projection을 삭제하면 안 된다.

## 기존 DB 전환 순서

1. 기존 DB 백업 후 모든 앱 인스턴스·worker·외부 생산자의 해당 쓰기를 중지한다. 실행 중 작업이 없는지 확인한다.
2. v1 전체 스키마와 실제 데이터 상태를 확인하고 전환 준비 SQL을 실행한다. 사용자 벡터/선호 revision/lease를 합치고, 현재 설명과 일치하는 매장 벡터만 복사한다.
3. 현재 동의 버전과 기존 이벤트·jobId를 옮긴다. 완료 작업이 이미 삭제됐더라도 기존 match_job의 이벤트는 work로 복원한다. 준비 SQL 끝의 비교 결과와 복사된 캐시 메타데이터를 확인한다. 잘못된/고아 SLOT payload나 중복 작업이 있으면 먼저 정합화한다.
4. 아래 Java 변경을 배포하고, 격리 환경에서 등록/수정/취소·벡터 생성·매칭·중복 이벤트·재시도·예약 완료를 검증한다. 전환 당시 남아 있는 INDEX/STORE_VECTOR 작업도 새 문서 조회 경로로 처리되는지 확인한다.
5. 서비스 재개 후 v1 테이블을 읽거나 쓰는 코드가 없고, 신규 데이터·큐·캐시가 정상인지 확인한다. 롤백은 구 코드로 단순 재시작하는 대신 백업과 전환 이후 쓰기를 고려한 절차를 따른다.
6. 검증 후 `search_consent`, `search_vector`, `search_preference_revision`, `search_vector_generation`, `search_match_job`, `search_store_projection`을 제거한다. 전환 마무리 SQL 05에 해당 DROP이 포함된다. 최종 search 테이블은 7개다. 준비 단계에서는 구 테이블을 보존하므로 일시적으로 13개다.

### 함께 전환한 코드

- `WaitlistService`/`SearchRepository`: 동의 이력 INSERT 제거, intent의 consent_version 저장·발송 검증.
- `PersonalizationService`: revision·벡터·lease를 user_vector 단일 행으로 처리. 빈 선호의 상태 행 보존.
- `MatchService`/`SearchWorker`: match_job 조회·갱신을 work로 통합하고 기존 공개 jobId 유지.
- `SearchIndexService`: store_projection 의존 제거, store 최신 문서 조회와 store_vector 캐시 연결.
- `SearchSchemaVerifier`, H2 테스트 DDL, 계약·동시성·Oracle/ES 통합 테스트: v2 구조로 갱신.

## 검증 기록

2026-10-16 실제 로컬 Oracle의 격리된 임시 스키마에서 검증했다. 앱 스키마는 변경하지 않았다.

- 신규 DDL: 7개 테이블 생성, 잠금 256행, 불완전한 사용자 벡터 메타데이터 거절 확인.
- 전환 준비 SQL: 캐시 존재 사용자와 생성 대기 사용자, 선호 revision·생성 lease 복사 확인.
- ACTIVE와 CANCELED 대기의 마지막 동의 버전 보존 확인.
- 현재 설명에 맞는 매장 벡터만 복사하고 오래된 설명의 벡터는 제외하는 것 확인.
- 기존 SLOT 작업의 공개 jobId 보존과 큐에서 사라진 완료 이벤트 복원 확인. nullable 발행 버전도 보존.
- 검증용 임시 스키마는 삭제했다. 임베딩 샘플은 SQL 복사 검증용이며 실제 1,536차원 벡터 내용 검증은 애플리케이션 책임이다.

이번 코드 전환에서 `./gradlew format test build --console=plain`이 통과했다. 실제 Oracle의 새 스키마 생성과 데이터가 있는 v1 → 04 → 05 전환도 격리 스키마에서 검증했다. 앱 DB 반영 결과는 아래 실행 기록에 별도로 남긴다.

## 로컬 적용 기록

2026-10-16 22:06 KST, `localhost:1521/FREEPDB1/BINTIME_APP`에 migration 04·05를 적용했다. 앱 연결은 없고 DataGrip 유휴 연결만 있는 상태에서 진행했다. 기존 search 업무 행은 모두 0개였으며 잠금 행만 256개였다. 기존 구조 복구용 DDL과 테이블별 행 수를 `/private/tmp/bintime-search-v1-backup-20261016-220624`에 보관했다.

- 최종 테이블: **20개 = 기존 13개 + search 7개**. 레거시 `SEARCH_WAITLISTS` 포함.
- 제거한 구 search 테이블 6개가 없는 것을 확인했다.
- 기존 13개 테이블과 유지한 search 테이블의 행 수는 동일하다. 잠금 0~255도 256행이다.
- 격리 Oracle에서는 합성 데이터로 캐시·revision·lease·동의·jobId 복사를 검증했고, 실제 Oracle JDBC의 사용자 벡터 흐름 11개 테스트와 대기 동시성·멱등성 테스트 1개가 통과했다.
- 실제 OpenAI 유료 호출은 이번 전환에서 재실행하지 않았다. 실제 사용자 인증부터 알림 수신까지의 통합 검증은 상대 도메인 연결 후 수행한다.
