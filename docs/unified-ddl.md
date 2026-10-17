# 통합 Oracle DDL

2026-10-16 기준. [infra/oracle/ddl.sql](../infra/oracle/ddl.sql)은 신규 빈 앱 계정에 **25개 기본 테이블**을 생성한다. 기존 초기 DDL, search v2, 마이그레이션 9개(notification 003까지)의 결과를 한 파일에 반영했다. 외부 SQL 파일을 불러오는 `@`/`@@` 명령 없이 단독 실행한다.

**텍스트 채팅 v1에는 통합 DDL 다음에 [004-chatting.sql](../infra/oracle/migrations/004-chatting.sql)을 추가 실행해야 한다.** 통합본에는 채팅 v1의 상태·최근 메시지 시각 컬럼과 읽음·HTTP 멱등성 테이블이 포함되어 있지 않다. 004 적용 후에는 전체 **27개 테이블**, 채팅 **4개 테이블**이 된다. 기존 OCI 공유 DB처럼 이미 004를 적용한 환경에는 통합 DDL을 다시 실행하지 않는다.

**예약 제안 기능에는 004 다음 [08-chat-booking-proposals.sql](../infra/oracle/migrations/08-chat-booking-proposals.sql)이 추가로 필요하다.** 제안 테이블 1개와 예약 설명 컬럼을 추가해 전체 28개, 채팅 5개가 된다. 새 코드 배포 전에 로컬·OCI 각각 적용하며 기존 데이터는 유지한다. 아래의 통합본 대조·QA 기록은 08을 포함하지 않은 당시 기준이다. 추가 계약은 [채팅 DB 안내](chatting-db.md)를 따른다.

## 실행 대상과 방법

1. 환경에 맞는 앱 계정·기본 테이블스페이스·quota·권한을 준비한다. 로컬은 [01-app-user.sh](../infra/oracle/local-setup/01-app-user.sh), Autonomous DB는 [app-user-grants.sql](../infra/oracle/app-user-grants.sql)을 참고한다. 계정명과 테이블스페이스는 환경마다 다르므로 통합 DDL에 하드코딩하지 않는다.
2. DataGrip/IntelliJ에서 **테이블이 없는 앱 계정**에 접속해 `infra/oracle/ddl.sql` 전체를 스크립트로 실행한다. SQL*Plus에서는 호출 측에 `SET DEFINE OFF`, `WHENEVER SQLERROR EXIT SQL.SQLCODE`를 설정한다.
3. 마지막 `COMMIT;`까지 실행한다. search 잠금 버킷 0~255 외에 사용자·매장·예약 등의 시연 데이터는 넣지 않는다.
4. `infra/oracle/migrations/004-chatting.sql` 전체를 같은 앱 계정에서 실행한다. 실행 중에는 채팅 쓰기를 중지한다. [verify-chatting.sql](../infra/oracle/verify-chatting.sql)로 채팅 v1을 확인한다.
5. 애플리케이션은 기존 `ddl-auto=validate`를 유지한다. 앱의 search 시작 검증도 그대로 사용한다.

통합본 실행 후 `local-setup/01ddl.sql`, `docs/sql/search-oracle*.sql`과 이미 반영된 마이그레이션은 추가 실행하지 않는다. **통합본에 포함되지 않은 채팅 004는 위 순서대로 별도 적용한다.** 기존 DB 업데이트에는 현재 적용 상태에 맞는 마이그레이션을 사용한다. 통합본은 DROP/초기화/기존 데이터 이전 스크립트가 아니며 재실행용 조건부 DDL도 아니다.

로컬 Docker의 기존 startup 마운트와 과거 QA 로더는 이번 작업에서 변경하지 않았다. 컨테이너 시작만으로 통합 DDL이 앱 계정에 자동 적용되는 것으로 간주하지 않는다. 기존 분산 SQL은 변경 이력 및 대조 검증을 위해 보존한다.

시연용 데이터가 필요하면 통합본과 채팅 004 적용 후 [더미 INSERT](../infra/oracle/dummy-data.sql)를 실행한다. 데이터 건수·기준 좌표·시나리오·별도 Oracle 검증은 [더미데이터 안내](demo-data.md)를 따른다.

## 원본 파일별 반영 내역

| 원본 | 통합본에 반영한 최종 결과 |
| --- | --- |
| `infra/oracle/local-setup/01ddl.sql` | 기본 5개 도메인과 store 확장 13개 테이블, 기본값, PK/UQ/FK/CHECK, 함수 기반 유일 인덱스, 주석 |
| `docs/sql/search-oracle.sql` | 최신 search v2 7개 테이블, 모든 제약·인덱스와 잠금 256행 |
| `docs/sql/search-oracle-v2.sql` | 현재 위 파일과 바이트 단위로 동일. 한 번만 반영 |
| `docs/sql/search-oracle-v1.sql` | 전환 이전 11개 테이블의 이력. 유지할 구조는 v2로 반영하고 제거된 구조는 생성하지 않음 |
| `001-user-profile.sql` | `version_no DEFAULT 1 NOT NULL`, `accuracy_meters`, `location_source`, `location_saved_at`; 버전·정확도·위치 메타데이터 제약 3개 |
| `02-store-api.sql` | 영업시간/시간대/평점/버전, 시술 활성/버전, 빈자리 원시술·원가격·발행·할인, 예약 시술·시간·고객·취소 스냅샷, 고객 잠금·멱등·이벤트 테이블, 제약·인덱스, 기존 대기 전용 FK 제거 |
| `03-user-vector-generation.sql` | v1 생성 lease가 최종 `search_user_vector.lease_owner/lease_until`로 통합됨. 제거된 `search_vector_generation`은 만들지 않음 |
| `04-search-v2-prepare.sql` | `search_user_vector`/`search_store_vector`, 현재 동의 버전, work의 이벤트·job·슬롯·발행 버전과 유일성/종류별 제약 |
| `05-search-v2-finalize.sql` | 잠금 범위, 대기 상태/버전, 작업 상태/시도/발행 버전 제약. 제거 대상 구 테이블 6개를 생성하지 않음 |
| `06-store-search-catalog.sql` | `store_shops.search_version NUMBER(19,0) DEFAULT 1 NOT NULL`과 1 이상 CHECK |
| `07-store-event-delivery.sql` | 이벤트 `store_id NOT NULL`, 시도·실행 가능 시각·lease·오류 정보, nullable 슬롯/슬롯 버전, 매장 FK와 전달 인덱스 |
| `002-notification-subscriptions.sql` | FCM project/FID, legacy Web Push 4개 필드 nullable, 세션 연결 테이블·FK·인덱스, FID 유일 인덱스 2개, project/FID 쌍 CHECK, 주석 |
| `003-notification-delivery.sql` | 알림 store/snapshot/request, nullable 발행 버전, legacy 대기 FK 제거, dedup/SSE events/deliveries/delivery clock 4개 테이블과 제약·인덱스 |

`infra/oracle/app-user-grants.sql`과 `local-setup/01-app-user.sh`는 계정/권한/테이블스페이스 준비용이다. 앱 데이터 모델 DDL과 실행 주체가 다르므로 통합 파일에 섞지 않았다. 시퀀스는 원본 앱 스키마에서 사용하지 않으며 기존 ID 기본값인 `LOWER(RAWTOHEX(SYS_GUID()))`를 유지했다.

## 통합 DDL 자체의 테이블 목록 (25개)

| 영역 | 개수 | 테이블 |
| --- | --- | --- |
| user | 1 | `user_accounts` |
| store | 7 | `store_shops`, `store_services`, `store_slots`, `store_reservations`, `store_customer_locks`, `store_idempotency`, `store_events` |
| 기존 대기 호환 | 1 | `search_waitlists` |
| search v2 | 7 | `search_user_lock`, `search_intent`, `search_idempotency`, `search_work`, `search_offer`, `search_user_vector`, `search_store_vector` |
| notification | 7 | `notification_alerts`, `notification_subscriptions`, `notification_subscription_sessions`, `notification_dedup`, `notification_events`, `notification_deliveries`, `notification_delivery_clock` |
| chatting | 2 | `chatting_rooms`, `chatting_messages` |
| 합계 | **25** | 기존 대기 호환 테이블과 v2 대기를 모두 유지 |

`004-chatting.sql`을 별도 적용하면 `chatting_read_positions`, `chatting_idempotency`가 추가되어 채팅은 4개, 전체는 27개 테이블이 된다. 아래 통합 DDL 비교·검증 수치는 004 적용 전의 25개 기본 테이블에 대한 기록이다.

생성하지 않는 v1 테이블은 `search_consent`, `search_vector`, `search_preference_revision`, `search_vector_generation`, `search_match_job`, `search_store_projection`이다. 현재 동의는 intent, 매칭 job 식별자는 work, 사용자 벡터·revision·lease는 user_vector, 매장 벡터 캐시는 store_vector에 반영한다.

## 중점 확인한 기본값과 관계

- 사용자 버전 1, 역할 CUSTOMER, 기본 선호 JSON, 매장 7일 휴무 JSON·Asia/Seoul·평점 0·업무 버전 1·검색 버전 1을 보존했다.
- 시술 활성 Y·버전 1, 일반 슬롯 AVAILABLE·REGULAR·버전 1, 예약 CONFIRMED·버전 1, 이벤트 QUEUED·시도 0·실행 가능 시각 SYSTIMESTAMP를 보존했다.
- 알림 PENDING·시도 0, 구독 활성 Y, 기기별 발송 시도 0, 사용자 벡터 revision 0을 보존했다. 원본에 기본값이 없는 상태·시간·가격에는 새 기본값을 만들지 않았다.
- `NUMBER` 정밀도/scale, `VARCHAR2` BYTE/CHAR 길이, `TIMESTAMP(6) WITH TIME ZONE`, CLOB, RAW(32), nullable 여부와 DEFAULT 식을 Oracle 메타데이터로 비교했다. search/worker의 epoch milliseconds NUMBER와 store 시간대 포함 TIMESTAMP를 서로 바꾸지 않았다.
- 예약 슬롯 1건 확정용 함수 기반 유일 인덱스, 원예약당 빈자리 1개, 예약 대기 유일성, FCM 구독 유일성, 사용자+슬롯 알림 dedup, 알림+구독 발송 유일성을 보존했다.
- 예약·알림의 legacy `search_waitlists` 전용 FK는 최종 마이그레이션대로 제외했다. 현재 대기 소유권·동의·조건은 공개 search 협력 서비스에서 검사한다. 레거시 `search_waitlists` 자체와 그 FK는 유지한다.
- `notification_alerts.publication_version`, 이벤트의 `slot_id`/`slot_version`, legacy Web Push 필드는 최종 계약대로 nullable이다. 임의로 NOT NULL을 추가하지 않았다.
- 알림의 `store_id`·snapshot/request 및 delivery의 subscription ID 등에 원본에 없는 FK/NOT NULL/JSON CHECK를 임의로 추가하지 않았다. 마이그레이션 결과와 같은 구조를 유지한다.

## 신규 생성에 필요 없는 데이터 이전문

마이그레이션을 단순히 이어 붙이지 않았다. 다음은 **기존 행을 변환하는 작업**이므로 빈 DB용 DDL에는 실행문을 넣지 않았다. 대신 변환 후의 최종 컬럼·제약을 생성한다.

| 기존 DB에서의 데이터 변환 | 통합본에서의 처리 |
| --- | --- |
| 프로필의 기존 좌표를 MANUAL·저장 시각으로 보정 | 신규 테이블은 비어 있음. 위치 메타데이터 CHECK는 포함 |
| 기존 예약의 시술/소요시간/고객 이름과 빈자리 원가격/발행 시각 보정 | 최종 컬럼·필수 여부·가격/시간 스냅샷 구조 포함 |
| 기존 이벤트의 매장 ID 보정 | 처음부터 `store_id NOT NULL`과 매장 FK 생성 |
| v1 벡터/선호/lease·동의·매칭 job 복사 | 최종 v2 테이블·컬럼·제약을 직접 생성 |
| 기존 Web Push 구독 비활성화 | 신규 구독 없음. FCM 등록 필드와 nullable legacy 필드 포함 |
| 기존 알림의 매장 ID 및 dedup 원장 보정 | 빈 알림/dedup 테이블 생성 |
| 전환 완료 후 구 search 테이블 DROP | 구 테이블을 처음부터 생성하지 않음 |

## 발견한 기존 파일의 차이

`02-store-api.sql`은 `IX_STORE_EVENT_QUEUE(status_code, created_at)`를 만들지만 최신 `local-setup/01ddl.sql`에는 이 인덱스가 없다. `07-store-event-delivery.sql`은 새로운 `IX_STORE_EVENT_DELIVERY(status_code, available_at, lease_until)`를 추가하면서 기존 인덱스를 제거하지 않는다.

통합본은 **전체 마이그레이션 적용 결과**에 맞춰 두 인덱스를 모두 유지한다. 현재 분산 설치와의 Oracle 비교에서 유일한 차이는 이 인덱스 하나다. 시간대 포함 컬럼 인덱스는 Oracle에서 내부적으로 `SYS_EXTRACT_UTC(...)` 함수 기반 표현으로 나타나므로 단순 컬럼명뿐 아니라 실제 표현식도 비교했다.

`03-user-vector-generation.sql`의 “신규 DDL에 이 테이블 포함” 주석은 v1 당시의 설명이다. 최신 v2에서는 해당 테이블이 제거되며 생성 lease는 `search_user_vector`에 있다. 신규 통합본 뒤에 03을 실행하지 않는다.

## 실제 Oracle 검증

아래는 **채팅 004 이전의 25개 기본 테이블**을 비교한 기존 검증 범위와 실행 기록이다. 현재 `verify-unified-ddl.py`의 마이그레이션 목록에는 004가 없어 파일 목록 검사에서 `Update verification for added/removed migrations`로 중단된다. 검증기 목록·비교 경로를 갱신하기 전에는 현재 채팅까지 검증한 결과로 해석하지 않는다. 이 작업에서는 기존 SQL·검증 스크립트를 변경하지 않았으며 채팅 004는 [별도 검증 SQL](../infra/oracle/verify-chatting.sql)을 사용한다.

[verify-unified-ddl.py](../infra/oracle/verify-unified-ddl.py)는 앱 DB에 접근하지 않고 로컬 Oracle 컨테이너 안에 매번 새 임시 계정을 생성한다. 다음 세 경로를 비교하고 생성한 계정은 종료 시 제거한다.

1. **전체 마이그레이션 경로**: 과거 초기 DDL(`23e7d84`) → store 02 → search v1 → user 001 → search 03·04·05 → store 06·07 → notification 002·003. v1 스냅샷에 이미 포함된 generation 테이블 생성문 하나만 분리해서 03 파일 자체도 실행한다.
2. **현재 분산 설치 경로**: 최신 `local-setup/01ddl.sql` + 최신 search DDL + 필요한 user/store/notification 마이그레이션.
3. **통합 경로**: 신규 `infra/oracle/ddl.sql` 하나.

검증 항목은 테이블, 전체 컬럼의 타입·길이·기본값·nullable, PK/UQ/FK의 컬럼 순서와 참조 대상·삭제 규칙, CHECK 식과 활성/검증 상태, 인덱스의 유일성·컬럼 순서·정렬 방향·함수 표현식, 잠금 초기값이다. Oracle이 자동 생성하는 제약/인덱스 이름과 컬럼 생성 순서는 구조 의미에 영향을 주지 않으므로 비교에서 정규화한다. CHECK가 참조하는 컬럼 집합은 순서가 없지만 복합 PK/UQ/FK·인덱스의 순서는 유지해서 비교한다.

실행 예:

```sh
# Oracle 컨테이너가 실행 중이어야 한다.
python3 infra/oracle/verify-unified-ddl.py

# 통합 DDL로 만든 추가 임시 Oracle DB에서 store API·경합 테스트까지 실행
python3 infra/oracle/verify-unified-ddl.py --run-store-tests

# 과거 Git 이력이 없는 환경에서는 초기 store 구현 이전 DDL을 명시
python3 infra/oracle/verify-unified-ddl.py --legacy-ddl /path/to/original-01ddl.sql
```

컨테이너 기본값은 `bintime-dev-oracle-1`이며 `STORE_ORACLE_CONTAINER`로 변경한다. `--run-store-tests`의 호스트 포트 기본값은 1521이며 `STORE_ORACLE_PORT`로 변경한다. 생성 계정명은 `BT_DDL_` 또는 기존 StoreApiTests 전용 `BT_STORE_` 접두사를 사용한다. 비교 경로에서는 실제 앱 사용자로 접속하므로 마이그레이션의 `USER_*` 딕셔너리 조회까지 실행된다.

기본 JSON 결과는 `/private/tmp/bintime-unified-ddl-verification.json`에 저장하며 `--report`로 바꿀 수 있다. 각 원본과 통합본의 SHA-256, 세 경로의 실제 메타데이터, 차이를 기록한다. 임시 비밀번호는 결과에 저장하지 않는다. 마이그레이션 파일이 추가·삭제되거나 search v2 두 별칭이 달라지면 검증을 실패시켜 대조 목록 갱신을 요구한다.

2026-10-16 실행 결과:

- 전체 마이그레이션 경로와 통합 경로: **차이 없음**.
- 현재 분산 설치 경로와 통합 경로: 누락 없음. 통합본에 `IX_STORE_EVENT_QUEUE` 1개 추가 보존.
- **25개 테이블, 227개 공개 컬럼, 292개 제약조건, 86개 인덱스** 확인. 제약조건 수에는 Oracle의 NOT NULL 제약이, 인덱스 수에는 PK/UQ·LOB 등의 자동 인덱스가 포함된다. SQL에 직접 작성한 CREATE INDEX는 31개다.
- 모든 스키마 객체 VALID, 잠금 버킷은 정확히 0~255의 256행, 나머지 24개 테이블은 모두 0행.
- 통합본으로 생성한 실제 Oracle 스키마에서 `ddl-auto=validate`와 `StoreApiTests` 17건 통과. API·동시 예약·멱등성·만료 경계·대기 연동/롤백 포함.
- `./gradlew format test build --rerun-tasks --console=plain` 통과. 전체 244건 중 240건 통과, 외부 연동 조건부 4건 제외.
- `git diff --check`와 검증 스크립트의 Python 구문 검사 통과. 최종 SQL의 포맷 정리 후에도 Oracle 3경로 대조를 다시 실행해 같은 결과 확인.

검증 DB는 로컬 Oracle Free 컨테이너다. Autonomous DB에 직접 적용하거나 기존 앱 스키마를 변경한 결과가 아니다. 통합 DDL은 기존과 같이 19c+ SQL 부분집합을 사용하지만 배포 대상 DB에서의 실행 결과까지 보장한 기록으로 해석하지 않는다.
