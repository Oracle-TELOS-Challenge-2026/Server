# 해커톤 시연용 더미데이터

[infra/oracle/dummy-data.sql](../infra/oracle/dummy-data.sql)은 [통합 DDL](../infra/oracle/ddl.sql)로 만든 빈 앱 스키마에 실행하는 INSERT 파일이다. 실제 고객·카카오 ID·FCM FID·벡터를 흉내 낸 값은 넣지 않는다. 시연의 시작 상태를 준비하고 취소·발행·매칭·알림·예약의 결과는 실제 서비스가 만든다.

## 실행 순서

1. 시연용 빈 앱 스키마에 `infra/oracle/ddl.sql` 전체 실행.
2. 앱 쓰기와 worker가 실행되지 않는 상태에서 `infra/oracle/dummy-data.sql` 전체를 **스크립트로** 실행. DataGrip에서는 `DECLARE ... END;` 전체를 선택해 실행하거나 스크립트 실행을 사용한다. 끝의 `/`는 PL/SQL 블록 구분자다.
3. 앱을 시작하기 전에 `infra/oracle/verify-dummy-data.sql`을 실행해 초기 상태 검증.
4. 시연용 앱을 연결하고 아래 시나리오 진행.

SQL*Plus에서는 호출 측에 `SET DEFINE OFF`, `WHENEVER SQLERROR EXIT SQL.SQLCODE`를 설정한다. INSERT 파일은 PL/SQL 블록 하나이며 기준 시각을 한 번만 잡는다. 마지막 COMMIT 전 실패하면 전체 INSERT를 ROLLBACK한다. 다른 미커밋 작업과 섞지 않는다. 기존 데이터가 있는 24개 업무 테이블 중 하나라도 비어 있지 않으면 시작 전에 거절한다. 테이블을 지우거나 기존 데이터를 수정하지 않는다.

이 파일은 재실행용 MERGE가 아니다. 시연 후 처음 상태가 필요하면 별도 빈 스키마를 준비한다. 운영 DB나 이미 사용 중인 앱 DB에 초기 데이터를 섞는 용도가 아니다.

## 기준 위치와 날짜

사용자 지정 기준점은 **위도 37.51335413975466, 경도 126.92649449142719**다. `demo_shop_01`, 대부분 고객의 저장 위치, 지역 검색 고객 02의 대기 조건이 이 점을 기준으로 한다. 거리 불일치 고객 06은 먼 위치를 별도로 가진다.

통합 DDL의 매장/사용자 좌표는 NUMBER 소수점 6자리이므로 `37.513354, 126.926494`로 저장된다. `search_intent.conditions_json`의 기준 위치는 사용자가 지정한 전체 정밀도를 유지한다. 스키마 정밀도는 변경하지 않았다.

| 매장 | 기준점 대비 배치 | 시연 목적 |
| --- | --- | --- |
| `demo_shop_01` | 기준점 | 메인 취소·할인·예약 |
| `demo_shop_02` | 북쪽 약 183m, 동쪽 약 221m | 인근 대안·이미 발행된 빈자리 |
| `demo_shop_03` | 남쪽 약 206m, 서쪽 약 220m | 철회·재발행 |
| `demo_shop_04` | 북쪽 약 517m, 서쪽 약 396m | 일반 공급·과거 취소·노쇼 |
| `demo_shop_05` | 기준 반경 1,500m 밖 | 거리 불일치 |
| `demo_shop_06` | 기준 반경 1,500m 밖 | 거리 불일치·비활성 시술 |

매장 이름·주소·설명은 가상의 시연 데이터다. 위 좌표에 실제 해당 상호가 있다는 의미가 아니다. 평점은 통합 DDL 기본값인 0을 유지한다.

`D`는 **INSERT를 실행한 한국 날짜의 다음 날 00:00**이다. 메인 예약은 D 14:00~15:00, 다른 일반 공급은 주로 D+1이다. 과거 데이터는 D-2~D-3에 둔다. 예를 들어 2026-10-17에 실행하면 D=2026-10-18이다. 모든 매장은 매일 09:00~20:00 영업한다. 기준일은 한 번 캡처하므로 SQL 실행 도중 자정이 지나도 각 행의 날짜가 갈라지지 않는다.

## 생성 건수

| 테이블 | 건수 | 구성 |
| --- | ---: | --- |
| `user_accounts` | 20 | 사장님 6명·고객 14명 |
| `store_shops` | 6 | 근처 4개·기준 반경 밖 2개 |
| `store_services` | 18 | 매장마다 케어 30분/30,000원·젤 60분/60,000원·아트 90분/90,000원 |
| `store_slots` | 40 | 예약 원슬롯 16·파생 빈자리 6·탐색용 일반 슬롯 18 |
| `store_reservations` | 16 | CONFIRMED 8·CANCELED 4·COMPLETED 2·NO_SHOW 2 |
| `search_intent` | 20 | ACTIVE 14·CANCELED 2·EXPIRED 2·FULFILLED 2 |
| `search_user_lock` | 추가 0 | 통합 DDL의 기존 256행 유지 |
| 나머지 18개 테이블 | 0 | 실제 기능 실행으로 생성하거나 이번 store 시연 범위에서 제외 |

INSERT 합계는 **120행**이며 잠금 테이블을 포함한 전체 초기 행 수는 376행이다. 알림 7개 테이블, store 고객 잠금/멱등/이벤트, search 레거시 대기/멱등/work/offer/벡터 2개, chatting 2개 테이블은 비워둔다.

시술 ID 규칙은 `demo_service_{매장번호}_{care|gel|art}`다. `demo_service_06_art` 하나만 비활성이다. 일반 공급이 AVAILABLE이어도 비활성 시술은 검색/예약 대상에서 제외되는지 확인한다.

## 메인 시연: 취소를 새 예약으로 연결

출발 상태:

- 소유자: `demo_owner_01`
- 기존 고객: `demo_customer_14`
- 예약: `demo_reservation_01`, CONFIRMED, version=1
- 원슬롯: `demo_slot_reserved_01`, BOOKED, version=2
- 매장/시술: `demo_shop_01` / `demo_service_01_gel`
- 일정/정가: D 14:00~15:00 / 60,000원

1. 소유자 또는 기존 고객으로 `POST /api/v1/reservations/demo_reservation_01/cancel`을 실행한다. 소유자는 `reasonCode=STORE_REQUEST`, `expectedVersion=1`을 사용한다. 응답의 **새 vacancy ID와 version**을 저장한다.
2. `POST /api/v1/owner/stores/demo_shop_01/vacancies/{새ID}/publish`에 시술 ID, 응답의 availableFrom, 할인 `{type:"PERCENT",value:30}`, 응답의 version을 전달한다.
3. 발행 후 가격은 **60,000 → 42,000원**, publicationVersion=1, 슬롯 version=2다. outbox/search worker가 실행되면 실제 매칭 작업이 시작된다.
4. 고객 01/02/03에게 로그인 세션·FID가 연결되어 있다면 일치 고객 모두가 알림 대상이다. 고객 13/14는 조건도 맞지만 구독 없는 비교군으로 둔다.
5. 고객 01 또는 02가 `POST /api/v1/reservations`에 **새 발행 슬롯 ID**, 최신 `expectedSlotVersion`, `expectedPrice=42000`, 본인의 `demo_wait_01` 또는 `demo_wait_02`를 전달한다.
6. 예약 하나만 CONFIRMED가 되고 해당 대기는 FULFILLED가 된다. 다른 고객의 재예약은 SLOT_UNAVAILABLE, 목록 재조회에서는 마감 상태로 보인다.

취소/발행/예약 요청에는 API 계약의 인증과 `Idempotency-Key`를 사용한다. 세션 쿠키 방식의 변경 요청은 CSRF 토큰도 필요하다. 같은 요청을 재전송할 때 같은 키를 사용하면 성공 응답 재생을 추가 시연할 수 있다.

### 메인 슬롯의 조건 비교군

| 대기 | 고객 | 의도한 조건과 결과 |
| --- | --- | --- |
| `demo_wait_01` | 01 서윤 | 해당 매장·젤·14~15시·45,000원 이하 → 일치 |
| `demo_wait_02` | 02 지우 | 지정 좌표 반경 1,500m·13~16시·45,000원 이하 → 일치 |
| `demo_wait_03` | 03 하린 | 해당 매장·젤·14~16시·50,000원 이하 → 일치 |
| `demo_wait_04` | 04 수빈 | 17~18시만 가능 → 시간 불일치 |
| `demo_wait_05` | 05 예린 | 40,000원 이하 → 가격 불일치 |
| `demo_wait_06` | 06 다은 | 먼 위치에서 반경 500m → 거리 불일치 |
| `demo_wait_07` | 07 채원 | 기본 케어만 원함 → 시술 불일치 |
| `demo_wait_08` | 08 민서 | 매장 02 지정 → 매장 불일치 |
| `demo_wait_13` | 13 지민 | 조건 일치·구독 없는 비교군 |
| `demo_wait_14` | 14 은서 | 조건 일치·구독 없는 비교군. 메인 취소 전에는 기존 예약자 |

키워드는 순위에 쓰는 선호다. 위 필수 조건을 무시하고 알림 대상을 넓히는 근거가 아니다. 수신 비교는 **01/02/03만 실제 구독을 준비한다는 전제**다. INSERT 직후에는 모든 고객이 미구독 상태이므로 푸시가 발송되지 않는다.

## 보조 시나리오

| 시나리오 | 데이터와 실행 | 예상 결과 |
| --- | --- | --- |
| 발행 대기 | `demo_vac_draft`, 매장 01, D 16~17시, version=1 | 발행 전 검색 제외. 30% 발행하면 고객 11의 `demo_wait_11` 일치 |
| 기존 빈자리 발견 | `demo_vac_open`, 매장 02, D 15~16시, 42,000원, version=2 | 고객 09의 `demo_wait_09`와 일치. API로 해당 대기를 재동의·수정하면 기존 슬롯 탐색 작업 생성 |
| 새 대기 등록 즉시 탐색 | 고객 10 등으로 `demo_vac_open`을 포함하는 **새 조건**을 등록 | 이미 공개된 공급도 즉시 탐색. 기존 동일 ACTIVE 조건을 다시 등록하면 중복 거절이므로 다른 조건 또는 신규 고객 사용 |
| 철회된 슬롯 | `demo_vac_withdrawn`, 매장 03, D 16~17시, version=3 | 검색/예약 제외. 재발행하면 publicationVersion=2이며 고객 10의 `demo_wait_10`과 일치 |
| 일반 슬롯 탐색 | `demo_slot_browse_04_care`, D+1 10~10:30, 30,000원 | 고객 12의 `demo_wait_12`에 노출 |
| 만료된 빈자리 | `demo_vac_expired_cancel`, `demo_vac_expired_noshow_1`, `demo_vac_expired_noshow_2` | 검색 제외·예약 거절 |
| 비활성 시술 | `demo_slot_browse_06_art` | AVAILABLE이어도 원장 재검증/공개 공급 조회에서 제외 |
| 예약 목록 | 고객 01/02의 완료 이력, 고객 03/04의 노쇼, 고객 11~14의 취소 이력 | 예정·과거·취소 목록과 사장님 타임테이블 비교 |
| 완료된 대기 | `demo_wait_19`↔`demo_reservation_02`, `demo_wait_20`↔`demo_reservation_03` | 양방향 연결, 상태 FULFILLED, 재알림 대상 제외 |

## 직접 INSERT 후 필요한 실제 서비스 준비

SQL은 애플리케이션 이벤트를 발행하지 않는다. 따라서 이미 넣은 공개 슬롯을 대상으로 초기 매칭·색인이 자동 완료된 상태를 만들지 않는다.

- 공개 검색 화면은 [search 안내](search.md)의 `--search.index.rebuild=true` 경로로 실제 store 원장을 색인한다. 매장 설명/사용자 선호 벡터는 실제 모델로 생성하며 가짜 1,536차원 값을 넣지 않는다. 벡터 생성은 이 INSERT 작업에서 실행하지 않는다.
- 이미 존재하는 대기는 API로 재동의·수정하거나 새로운 조건을 등록해야 기존 슬롯 탐색 작업을 생성한다. 반면 메인 시연의 취소·발행은 실제 API 호출이므로 이벤트를 생성한다.
- `kakao_subject`는 모두 NULL이다. SQL만으로 브라우저가 이 고객으로 로그인되지는 않는다. store/search 로컬 API 확인은 [로컬 인증](store-auth.md)의 명시적 개발 설정과 `X-Local-User-Id`를 사용할 수 있다.
- 실제 알림 시연은 [알림 연결 계약](store-notification-integration.md)에 따라 해당 고객과 연결된 실제 로그인 세션·FID·FCM 설정이 필요하다. 로컬 사용자 헤더만으로 알림 세션/기기 구독이 생기지는 않는다. 실계정과 더미 고객의 연결은 시연 환경에서 별도 준비한다.

## 검증 방법과 범위

```sh
# SQL 삽입·모든 테이블 건수·정합성·롤백·재실행 거절 검증
python3 infra/oracle/verify-dummy-data.py

# 실제 서비스로 메인 시나리오와 예약 경합까지 검증
python3 infra/oracle/verify-dummy-data.py --run-scenarios
```

실행 중인 로컬 Oracle 컨테이너가 필요하다. 기본 컨테이너는 `bintime-dev-oracle-1`, 호스트 포트는 1521이며 각각 `STORE_ORACLE_CONTAINER`, `STORE_ORACLE_PORT`로 변경한다. 검증은 임시 `BT_DEMO_` 계정에만 적용하고 종료 후 해당 계정을 제거한다. 기존 앱 DB에는 실행하지 않는다.

2026-10-17 검증 항목:

- 통합 DDL → INSERT → 검증 SQL 실행. 25개 테이블의 정확한 초기 행 수와 기존 잠금 256행 유지.
- 슬롯 INSERT 중 가격 제약 실패를 의도적으로 발생시켜 앞서 삽입한 고객·매장·시술까지 전체 롤백되는지 확인.
- 정상 삽입 후 재실행은 ORA-20101로 거절되며 기존 데이터 불변.
- 다른 세션 시간대(-07:00)에서도 날짜 기준은 Asia/Seoul 유지.
- 예약/공개 공급 겹침 없음, 예약·고객·시술 스냅샷, 취소 시각, 원예약/빈자리 관계, 할인 계산, 과거 완료·노쇼·만료, 대기 JSON·동의·예약 양방향 연결 확인.
- 실제 원장 카탈로그에서 기존 공개 빈자리·일반 공급 탐색, 철회·만료·예약·비활성 시술 제외.
- 사용자 지정 기준점이 대기 JSON에 유지되고 근처 4개·원거리 2개가 반경 1,500m로 구분되는지 확인.
- 메인 예약 취소 → 30% 할인 발행 → 조건 일치 대기 01/02/03/13/14와 불일치 04~08 구분 → 두 고객 동시 예약에서 단 한 명 확정·해당 대기 완료 확인.

실제 FCM 전송·기기 수신·브라우저 로그인·ES 색인·임베딩 생성은 이 데이터 검증에서 실행하지 않는다. 시나리오 테스트의 외부 AI와 검색 엔진은 대역을 사용하며 실제 store/search 서비스와 Oracle은 그대로 사용한다.

최종 실행 결과: Oracle 삽입·정합성·롤백·재실행 거절과 지정 좌표 기반 실제 서비스 시나리오 테스트가 통과했다. `./gradlew format test build --rerun-tasks --console=plain`도 통과했다. 기본 전체 테스트는 245건 중 240건 성공, 외부 연동 조건부 5건 제외이며, 새 `DemoDataOracleTests` 1건은 별도 Oracle 시나리오 실행에서 성공했다. `git diff --check`와 Python 구문 검사도 통과했다.
