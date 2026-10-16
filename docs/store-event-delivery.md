# store 이벤트 전달

`store_events`는 업무 트랜잭션에서 저장하는 outbox다. `StoreEventDelivery`가 커밋된 이벤트를 소비해 search의 영속 작업 큐에 전달한다. 실제 Elasticsearch·AI·알림 네트워크 호출은 이 소비자에서 수행하지 않는다.

## 전달 계약

- 매장 생성·수정, 시술 생성·수정: `STORE_CHANGED`, 전체 매장 문서 인덱싱 접수.
- 일반 슬롯 생성: `CREATED`, 전체 문서 인덱싱과 기존 활성 대기 대상 매칭 접수.
- 빈자리 발행: `PUBLISHED`, 전체 문서 인덱싱과 해당 발행 버전 매칭 접수.
- 예약·취소·완료·노쇼·철회·만료: 전체 문서 인덱싱 접수. 실제 알림 마감 전달은 별도 연결 범위다.

문서는 전달 시점의 원장을 다시 읽는다. 검색 문서의 단조 증가 `search_version`은 오래된 문서의 역전 반영을 막는다. 발행 매칭은 `revalidate`를 통과하고 현재 `publicationVersion`이 같은 경우에만 접수한다. 접수 후 변경도 search가 작업 실행 시 다시 검증한다. `MatchService.submit`의 이벤트 ID 중복 검증과 같은 DB 트랜잭션의 search 큐 저장·store ack로 재전달의 중복 효과를 막는다. `PROCESSED`는 search 큐 접수 완료이며 실제 매칭·색인·알림 완료가 아니다.

## 실패·재시작

기본 1초 간격으로 최대 20건씩 처리한다. 행 잠금으로 UUID lease를 발급하고 2분 동안 다른 인스턴스의 선점을 막는다. 프로세스 종료 후 lease가 만료되면 다음 실행에서 회수한다. 만료된 작업자가 뒤늦게 돌아와도 token이 바뀌었으면 상태를 변경하지 않는다.

전달 실패 시 search 큐와 ack를 롤백하고 `FAILED`로 기록한다. 2초부터 지수적으로 증가해 최대 5분 간격으로 무기한 재시도한다. 상태를 영구 실패로 막는 횟수 제한이나 수동 복구 API는 없다. 오류는 클래스 이름만 보관한다. `FAILED` 건수·가장 오래된 `created_at`·`last_error`를 확인해 원인(예: search 스키마 미적용)을 해결하면 자동 복구한다. lease claim 자체의 DB 장애는 다음 스케줄 실행에서 다시 시도된다.

`bintime.store.event-delivery-enabled=false`로 소비를 중지할 수 있다. 변경 이벤트 저장은 계속된다. `bintime.store.event-delivery-delay-ms` 기본값은 `1000`이다.

## DB 적용

기존 DB에는 앱을 중지한 상태에서 `07-store-event-delivery.sql`을 적용한다(06 선행). 기존 이벤트를 삭제하지 않고 슬롯에서 매장 ID를 보정한다. 새 DB는 최신 `local-setup/01ddl.sql`을 사용한다. 앱은 SQL을 자동 적용하지 않는다. 테스트가 아닌 앱용·운영 DB에 이번 변경으로 직접 SQL을 실행하지 않는다.

## 검증 기록

2026-10-16 `./gradlew format test build --no-daemon` 성공: 전체 164건 중 161건 통과, 외부 연동 3건 제외. 격리 Oracle의 기존 변경·신규 스키마 양쪽에서 StoreApiTests 26건 통과. 07 재실행·기존 이벤트의 매장 ID 보정과 보존·새 스키마에 변경 SQL 재적용도 확인했다. 앱 DB에는 적용하지 않았다.

`search.scheduling-enabled=false`는 다른 도메인의 스케줄러를 켜도 유지된다. search의 예약 실행 메서드는 해당 설정에 종속된 SearchScheduling Bean에만 등록하며, 회귀 테스트로 확인한다.
