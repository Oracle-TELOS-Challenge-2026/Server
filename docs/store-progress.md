# Store 구현 현황과 후속 작업

작성일: 2026-10-16. store API 구현과 직접 QA를 마친 시점의 기록이다. 완료 항목은 코드와 실행 결과를 기준으로 작성했고, 후속 작업의 우선순위는 제안이다. 다른 도메인의 진행 중인 변경까지 검증 완료로 간주하지 않는다.

이 문서는 **무엇을 구현했고 무엇이 남았는지**를 관리한다. 세부 동작·오류·배포 계약은 [store 구현 및 운영 계약](store.md), 직접 실행 방법은 [QA 안내](../qa/store/README.md)를 따른다.

## 1. 현재 도달한 상태

매장·시술·일반 슬롯을 등록하고, 고객이 예약한 뒤 취소하면 빈자리를 만들 수 있다. 사장님은 빈자리를 할인 발행·철회·재발행할 수 있고 다른 고객이 재예약할 수 있다. 이 흐름을 실제 Spring 서버와 Oracle에서 실행했다.

| 영역 | 현재 상태 |
| --- | --- |
| Store HTTP API | 기존 ST-01~17과 등록 API 3개, 총 20개 구현 |
| 원장 저장과 예약 정합성 | Oracle 저장, 고객·매장 잠금, 중복 예약 방어 구현 |
| 대기를 연결한 예약 | search 공개 협력 서비스의 조건 검증·완료 및 같은 트랜잭션 롤백 구현 |
| 후속 작업 저장 | 발행·철회·예약·취소·완료·노쇼·만료 이벤트 저장 구현 |
| 검색·매칭·실제 알림까지 이어지는 흐름 | store 조회 연동과 이벤트 전달이 아직 연결되지 않음 |
| 운영 인증 | 카카오 세션·CSRF 연결 및 선택적 Bearer JWT 검증 구현. 실제 배포 로그인 QA는 별도 |
| 직접 QA | 격리된 Oracle QA 서버 준비와 36개 순차 요청 모음 제공 |
| 운영 배포 | 로컬 검증 완료. 실제 배포 DB 반영·인증 개방·운영 연동은 별도 작업 |

`matchingStatus=QUEUED`는 의뢰를 DB에 저장했다는 의미다. 매칭·알림 발송이 완료되었다는 의미로 표시하면 안 된다.

## 2. 완료한 API

모든 응답은 공통 응답 봉투를 사용한다. 예약 생성과 등록 API는 201, 나머지 성공 응답은 200이다. 보호 API는 카카오 세션 쿠키 또는 설정된 Bearer JWT로 실행한다. 로컬 QA 대체 식별도 유지한다.

| 번호 | API | 구현한 동작 |
| --- | --- | --- |
| ST-01 | GET `/api/v1/stores/categories` | 카테고리 고정 목록 |
| ST-02 | GET `/api/v1/stores/{storeId}` | 매장 정보·위치·영업시간·평점 요약 |
| ST-03 | GET `/api/v1/stores/{storeId}/services` | 시술 목록과 커서 페이지네이션 |
| ST-04 | GET `/api/v1/stores/{storeId}/slots` | 날짜·시술 조건의 예약 가능 슬롯 |
| ST-05 | GET `/api/v1/stores/{storeId}/slots/{slotId}` | 최신 상태·가격·버전·예약 가능 여부 |
| ST-06 | POST `/api/v1/reservations` | 가격·버전·시간·대기 검증 후 예약 확정 |
| ST-07 | GET `/api/v1/reservations/me` | 예정·과거·취소 예약 및 전체 건수 |
| ST-08 | GET `/api/v1/reservations/{reservationId}` | 본인 또는 해당 매장 소유자의 예약 상세 |
| ST-09 | POST `/api/v1/reservations/{reservationId}/cancel` | 취소 및 남은 구간의 DRAFT 빈자리 생성 |
| ST-10 | PATCH `/api/v1/owner/stores/{storeId}` | 소개·주간 영업시간 변경 |
| ST-11 | GET `/api/v1/owner/stores/{storeId}/timetable` | 하루와 겹치는 예약·공급·빈자리 |
| ST-12 | GET `/api/v1/owner/stores/{storeId}/reservations/{reservationId}` | 소유자 전용 예약 상세·고객 표시 정보 |
| ST-13 | POST `/api/v1/owner/stores/{storeId}/reservations/{reservationId}/status` | 완료·노쇼 및 노쇼 빈자리 생성 |
| ST-14 | GET `/api/v1/owner/stores/{storeId}/vacancies` | 빈자리 목록·상태 필터·전체 건수 |
| ST-15 | POST `/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/publish` | 시술·남은 시간·할인 검증과 발행 |
| ST-16 | POST `/api/v1/owner/stores/{storeId}/vacancies/{vacancyId}/withdraw` | 미예약 발행 철회 |
| ST-17 | PATCH `/api/v1/owner/stores/{storeId}/services/{serviceId}` | 이름·정가·활성 여부 변경 |
| ST-18 | POST `/api/v1/owner/stores` | 요청자를 소유자로 매장 등록 |
| ST-19 | POST `/api/v1/owner/stores/{storeId}/services` | 시술 등록 |
| ST-20 | POST `/api/v1/owner/stores/{storeId}/slots` | 영업시간·중복을 검증한 일반 슬롯 등록 |

## 3. API 외에 완료한 작업

| 작업 | 구현 내용과 근거 |
| --- | --- |
| MVC 계층 | Controller가 요청을 전달하고 [StoreApplicationService](../src/main/java/com/bintime/store/service/StoreApplicationService.java)가 검증·트랜잭션을, StoreRepository가 영속 접근을 담당 |
| 영속 모델 | 기존 매장·시술·슬롯·예약 테이블 확장. 멱등 요청·고객 잠금·이벤트 테이블 추가 |
| 동시성 | 요청자/고객 → 매장 → 대상 리소스 → 대기 순서의 잠금. 같은 슬롯 및 다른 매장의 고객 중복 예약 방어 |
| 멱등성 | 동일 사용자·작업·키의 성공 응답을 24시간 재사용. 대상·본문 변경은 거절하며 실패 시 성공 기록을 남기지 않음 |
| 예약 스냅샷 | 예약 당시 시술명·소요시간·가격·고객 표시 이름 저장. 이후 시술 수정과 분리 |
| 할인 | 정률·정액 할인가 검증, 원 미만 버림, 입력한 정률 할인율 보존 |
| 상태·시간 | 완료·노쇼 가능 시각 검증, 빈자리 생성, 재발행 버전 증가, 만료 조회·정기 작업 |
| 조회와 입력 | 커서의 사용자·필터 검증, 범위·페이지 크기 제한, PATCH 생략과 null 구분, 버전 충돌 처리 |
| 데이터 노출 | 고객 응답의 고객 식별 정보·내부 자원 ID 제한, 사장님 소유권 검증 |
| 대기 협력 | [WaitlistReservationService](../src/main/java/com/bintime/search/service/WaitlistReservationService.java)가 기존 search_waitlists와 새 search_intent의 검증·완료를 담당 |
| 공통 오류·Swagger | 도메인 오류 코드와 HTTP 상태 구분, store의 501 설명 제거, 등록 API 문서 추가 |
| DB 변경 | [신규 초기 SQL](../infra/oracle/local-setup/01ddl.sql)과 [기존 DB 변경 SQL](../infra/oracle/migrations/02-store-api.sql) 제공. 기존 데이터 보존 및 ddl-auto=validate 유지 |
| QA 환경 | [시작 스크립트](../qa/store/start.py), QA 계정 SQL, Postman 컬렉션과 사용 안내 추가 |

예약의 waitlistId는 두 search 저장 형식을 지원하기 위해 기존 search_waitlists 전용 FK를 제거했다. 유일성은 유지하고 소유권·유효성은 search 협력 서비스가 같은 트랜잭션에서 검증한다.

## 4. 검증한 내용과 확인하지 않은 범위

다음은 **이미 실행한 검증의 기록**이다. 이 문서 작성 시 전체 테스트를 새로 실행한 결과는 아니며 이후 코드 변경에는 재검증이 필요하다.

| 검증 | 결과와 범위 |
| --- | --- |
| 저장소 표준 명령 | store 구현 마무리 시 `./gradlew format`, `./gradlew test`, `./gradlew build` 통과 |
| 당시 전체 테스트 | 55개 중 53개 통과, 외부 환경을 요구하는 search Oracle·Elasticsearch 테스트 2개 생략 |
| Store 통합 테스트 | [StoreApiTests](../src/test/java/com/bintime/store/StoreApiTests.java)의 17개 테스트로 20개 API 흐름 및 실패 경계 검증 |
| 실제 Oracle | [검증 스크립트](../infra/oracle/verify-store.py)로 기존 DB 변경·신규 생성 양쪽의 스키마 검증과 동일 store 테스트 통과 |
| 기존 데이터 보존 | 기존 예약의 시술명·시간·가격 및 빈자리의 가격·발행 정보를 변경 SQL 적용 후 확인 |
| 직접 HTTP QA | 실제 Spring 서버와 격리 Oracle에 요청 36개 실행, 컬렉션의 응답 검증 116개 통과 |

자동 테스트에서 검증한 주요 경계는 다음과 같다.

- 같은 슬롯에 대한 동시 예약, 같은 고객의 다른 매장 중복 예약, 예약과 철회의 경합.
- 같은 멱등 키의 동시 요청·성공 재생·다른 내용·실패 후 재시도·24시간 만료.
- 인접 예약 허용, 중복 공급 거절, 노쇼 10분 경계, 시작 시각 만료, 종료 후 취소.
- 가격 스냅샷, PATCH null, 버전 충돌, 커서 필터 불일치, 타임테이블 500개 상한.
- 대기 소유권·상태·조건 불일치와 JDBC search/JPA store의 공동 롤백.
- 정률 할인 반올림 오해 방지와 정액 할인가의 할인율 비표시.

직접 QA는 컬렉션의 HTTP 요청과 검증 스크립트를 실행한 결과이며 Postman 화면 조작 자체를 검증한 기록은 아니다. 완료·노쇼는 직접 QA에서 조기 거절을 확인했고, 성공과 시간 경계는 시간을 제어하는 통합 테스트에서 확인했다. 실제 시간을 기다린 성공 QA는 남아 있다.

운영 로그인·CSRF 전체 흐름, 실제 매칭·알림 전달, Elasticsearch와 연결한 최신 상태 반영, 배포용 Autonomous DB, 다중 앱 인스턴스·부하·장애 복구는 완료로 기록하지 않는다. 순차 HTTP QA는 동시성 시험을 대체하지 않는다.

## 5. 우선 구현할 연동 작업

아래 항목은 예약 원장에서 검색·알림·운영 사용까지 연결하기 위한 작업이다. 담당 영역은 협업 경계이며 특정 담당자·일정을 확정한 것은 아니다.

| ID | 우선순위·현재 상태 | 작업 | 완료 기준 |
| --- | --- | --- | --- |
| STORE-NEXT-01 | 구현 완료 · 배포 QA 대기 | 세션 쿠키·CSRF 연결 및 선택적 Bearer JWT 검증 구현. [인증 계약](store-auth.md) 참고 | 자동화 테스트 후 실제 카카오 로그인·배포 환경 QA 필요 |
| STORE-NEXT-02 | P0 · 인터페이스만 존재 | store 원장을 읽는 SearchStoreCatalog 구현. validate·available·revalidate·export·describe 제공 | search가 대기 조건의 매장·시술을 검증하고 실제 미래 활성 공급을 누락 없이 조회. 마감 슬롯은 재검증에서 제외 |
| STORE-NEXT-03 | P0 · 버전 계약 보완 필요 | 검색용 전체 매장 문서의 버전·갱신 조건 정리. 매장·시술·일반 슬롯·발행·예약·만료 변경 반영 | 검색에 영향을 주는 변경마다 단조 증가 버전의 전체 문서 제공. 늦은 이벤트가 최신 문서를 덮어쓰지 않음 |
| STORE-NEXT-04 | P0 · 저장만 구현 | store_events 소비·전달 처리. 발행은 search의 매칭 접수와 연결하고 철회·예약·만료의 마감 반영 경로 구현 | 커밋된 작업이 재시도 가능하게 전달됨. 중복·역순·오래된 발행을 무시하고 의뢰/처리 완료 상태를 구분 |
| STORE-NEXT-05 | P0 · 상대 도메인 협업 필요 | search/notification과 제안 마감·예약 결과 전달 및 화면 갱신 연결 | 예약·철회·만료된 공급이 알림/제안과 고객·매장 화면에서 마감됨. 늦게 클릭해도 원장의 재검증으로 중복 예약 차단 |
| STORE-NEXT-06 | P0 · 통합 검증 대기 | 실제 인증 및 검색·알림을 붙인 사용자 흐름 QA, 배포 DB 반영·기동 점검 | 대기 활성화 → 슬롯 발행 → 실제 제안/알림 → 예약 → 다른 고객의 마감 확인까지 검증. 배포 DB validate 통과 |

SearchStoreCatalog는 [계약 인터페이스](../src/main/java/com/bintime/store/contract/SearchStoreCatalog.java)는 있지만 실제 store 구현은 없다. 현재 [SearchConfiguration](../src/main/java/com/bintime/search/config/SearchConfiguration.java)의 기본 구현은 해당 호출에 의존 기능 미연결 오류를 반환한다. 따라서 store API와 대기 완료 협력이 구현되었다고 해서 search의 매장 탐색·매칭까지 연결된 것은 아니다.

검색 문서는 매장 정보뿐 아니라 시술·슬롯을 포함한다. 현재 매장 version은 매장 운영 정보 수정에 사용하므로 이를 그대로 검색 문서 버전으로 삼으면 다른 변경을 놓칠 수 있다. 검색 갱신용 버전을 어떻게 관리할지는 NEXT-03에서 정한다. 기존 매장 version의 의미를 조용히 바꾸지 않는다.

이벤트 전달은 이미 존재하는 search의 MatchService.submit, SearchIndexService.changed 같은 공개 협력 메서드를 검토해 연결한다. 예약 트랜잭션 안에서 외부 AI·Elasticsearch·알림 전송 완료를 기다리지 않고, 커밋된 영속 작업을 통해 전달한다. 소비자가 없는 현재 상태에서 QUEUED를 임의로 처리 완료로 바꾸지 않는다.

## 6. 운영 안정화와 추가 QA

| ID | 우선순위·현재 상태 | 후속 작업 | 완료 기준 |
| --- | --- | --- | --- |
| STORE-OPS-01 | P1 · 전달 처리와 함께 설계 | 이벤트 재시도·처리 임대·실패 조회·재처리 수단 | 작업 도중 앱 종료 후에도 복구되고 다중 소비자가 같은 효과를 중복 적용하지 않음 |
| STORE-OPS-02 | P1 · 보존 정책 미결정 | 멱등 성공 기록·처리 완료 이벤트·QA 스키마 보존 및 정리 방안 | 24시간 재생 보장을 지키면서 만료 데이터 정리 가능. 보존 기간·정리 범위 문서화 |
| STORE-OPS-03 | P1 · 기본 만료 작업 구현 | 매장별 실패 격리, 처리량·실행 시간 확인, 다중 인스턴스 동작 검증 | 한 매장 오류로 나머지 만료 처리가 계속 밀리지 않고 반복 실행에도 상태·이벤트 정합성 유지 |
| STORE-OPS-04 | P1 · 운영 관측 보완 | 예약 충돌·DB 잠금 대기·만료 지연·QUEUED 적체·전달 실패 관측 | 슬롯/이벤트 ID·버전으로 원인 추적 가능. 필요한 지표와 대응 절차 정의 |
| STORE-QA-01 | P1 · 실제 시간 성공 QA 남음 | 가까운 미래 예약으로 완료·노쇼 성공, 미예약 슬롯 만료 확인 | 시간 경과에 따라 상태와 DRAFT 빈자리·응답 버전·DB 결과가 일치 |
| STORE-QA-02 | P1 · 자동 경합 검증 완료 | 프론트와 stale 가격/버전, 중복 클릭·재전송·마감 화면 연동 | 충돌 후 최신 정보를 재조회하고 적절한 안내 제공. 예약 성공을 중복 표시하지 않음 |
| STORE-QA-03 | P1 · 운영 규모 검증 남음 | 목표 사용량에 맞춘 목록·타임테이블·예약 부하와 장애 복구 점검 | 합의한 응답 시간·오류율·정합성 기준 충족. 결과를 보고 필요한 최적화만 진행 |

이벤트 재시도 횟수·간격·최종 실패 판정, 처리 이력의 보존 기간, 운영 지연·성능 목표는 아직 확정하지 않았다. 구현 전에 정하고 계약·운영 문서에 반영한다.

## 7. 현재 제한과 범위 밖의 후보

다음은 현재 합의된 MVP 제한이다. 별도 요구 없이 미구현 버그나 필수 후속 작업으로 취급하지 않는다.

| 항목 | 현재 정책 |
| --- | --- |
| 매장 자원 | 매장당 소유자 1명·동시 예약 자원 1개. 직원별 예약 배정 없음 |
| 슬롯 확정 | 별도 HELD API 없이 하나의 트랜잭션에서 AVAILABLE → BOOKED |
| 영업시간 | Asia/Seoul, 요일별 1개 구간, 자정 넘김 없음 |
| 매장 수정 | 소개·주간 영업시간 수정만 제공 |
| 일반 슬롯 관리 | 직접 등록 제공. 자동 생성 및 별도 수정·차단·삭제 API는 이번 20개 범위에 없음 |
| 예약 변경 | 예약 확정·취소·완료·노쇼 제공. 별도 예약 일정 변경 API 없음 |
| 결제·가격 | 결제 없이 확정. 매장 입력 가격·할인만 사용 |
| 평점 | 초기 요약값 0 제공. 리뷰 작성·집계 기능 없음 |
| 외부 연동 | 외부 예약 시스템, 백그라운드 위치 추적, 행동 학습, 자동 할인 결정 제외 |

운영 화면에서 필요해지면 내 매장 목록, 매장 기본 정보 수정, 일반 슬롯 차단·수정, 휴게시간·임시 휴무·복수 영업 구간 등을 별도 요구사항으로 검토한다. 다중 직원·선점 유지·결제·예약 변경은 상태·동시성·권한 정책까지 다시 설계해야 하므로 단순 API 추가로 확정하지 않는다.

## 8. 다음 작업의 진행 순서

1. **현재 원장 검증을 기준선으로 유지한다.** QA 컬렉션과 StoreApiTests를 재현하고 대상 브랜치의 실제 변경 상태를 기록한다.
2. **인증 경계와 store 조회 연동을 연결한다.** NEXT-01·02를 진행하고 NEXT-03의 검색 문서 버전 계약을 확정한다.
3. **영속 이벤트를 소비하도록 구현한다.** NEXT-04와 OPS-01을 함께 진행해 전달 실패·재시도·중복을 검증한다.
4. **마감 반영과 실제 사용자 흐름을 검증한다.** NEXT-05·06으로 검색·알림·예약·화면 사이의 상태 일치를 확인한다.
5. **배포 전 운영 항목을 마무리한다.** 만료·적체 관측, 보존/정리 정책, 실제 시간 QA 및 필요한 부하 검증을 수행한다.

작업을 완료할 때는 위 표의 상태와 근거 파일·검증 결과를 갱신한다. 기능 완료와 상대 도메인 연결 완료, 자동 테스트 통과와 직접/운영 환경 확인을 구분해 기록한다.

## 9. 작업 재개 시 참고할 파일과 명령

| 목적 | 위치 |
| --- | --- |
| API 동작·운영 계약 | [docs/store.md](store.md) |
| API 번호·URL 매핑 | [docs/contracts/api.md](contracts/api.md) |
| store 유스케이스·트랜잭션 | [StoreApplicationService.java](../src/main/java/com/bintime/store/service/StoreApplicationService.java) |
| 인증 경계 | [CurrentActor.java](../src/main/java/com/bintime/common/auth/CurrentActor.java) |
| 대기 조건 검증·완료 | [WaitlistReservationService.java](../src/main/java/com/bintime/search/service/WaitlistReservationService.java) |
| 검색 협력 계약 | [SearchStoreCatalog.java](../src/main/java/com/bintime/store/contract/SearchStoreCatalog.java) |
| 자동 API·경합 검증 | [StoreApiTests.java](../src/test/java/com/bintime/store/StoreApiTests.java) |
| DB 변경 | [02-store-api.sql](../infra/oracle/migrations/02-store-api.sql) |
| 실제 Oracle 검증 | [verify-store.py](../infra/oracle/verify-store.py) |
| 직접 QA | [QA README](../qa/store/README.md), [Postman 컬렉션](../qa/store/Store-QA.postman_collection.json) |

```sh
# 표준 검증
./gradlew format
./gradlew test
./gradlew build

# store만 빠르게 검증
./gradlew test --tests 'com.bintime.store.StoreApiTests'

# 격리된 로컬 Oracle의 신규/변경 스키마 검증
# Git HEAD에 이미 최신 초기 SQL이 포함되었다면 STORE_BASELINE_SQL에 변경 전 SQL을 지정한다.
python3 infra/oracle/verify-store.py

# 실제 HTTP QA용 서버. 실행마다 새 QA 스키마를 만든다.
# 기본 포트 18081이 이미 사용 중이면 --port로 다른 포트를 지정한다.
python3 qa/store/start.py
```

신규 빈 DB에는 최신 초기 SQL만, 기존 초기 스키마에는 변경 SQL을 적용한다. 두 경로를 같은 DB에 연속 실행하지 않는다. 실제 운영 API 개방은 인증·CSRF 연결 및 배포 DB 검증을 마친 뒤 진행한다.
