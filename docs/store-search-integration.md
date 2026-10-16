# Store 이벤트 → Search → 실제 Elasticsearch 로컬 검증

2026-10-16 로컬에서 실행했다. 실제 Oracle 23 Free와 Elasticsearch 8.19.6을 사용하며 앱 스키마·운영 DB·기존 ES alias는 변경하지 않았다.

## 실행 결과

`StoreEventFlowTests.publicationBookingWithdrawalExpiryAndHttpFailureReachRealElasticsearch` 통과(실제 외부 환경 시나리오 1건, 실패 0).

이후 `./gradlew format test build --no-daemon`도 성공했다. 기본 전체 테스트는 167건 중 163건 통과·외부 환경 4건 제외이며, 그중 이 실제 Oracle·ES 시나리오는 위 별도 실행으로 통과했다.

| 검증 | 결과 |
| --- | --- |
| 매장·시술·일반 슬롯 등록 → 예약 → 취소 | 실제 HTTP API와 Oracle 원장 반영 확인 |
| DRAFT 빈자리 | ES 문서와 검색 API에서 미노출 |
| 발행 | outbox 전달·search worker 처리 후 ES 문서에 슬롯 포함, S-01에 노출 |
| 조건에 맞는 대기 | search_offer 저장 및 고객의 대기 상세에 제안 노출 |
| 같은 발행 이벤트 재전달 | SLOT 작업·매칭 제안 중복 생성 없음 |
| 예약 확정 | 취소하기 전 ES 문서와 검색에서 해당 슬롯이 빠지는 것 확인 |
| 철회 | ES 문서·검색·고객 대기 제안에서 제외 |
| 동일 빈자리 재발행 | 최신 publicationVersion으로 제안 갱신, 행은 하나 유지 |
| ES HTTP 503 | 테스트 전용 HTTP 프록시로 실제 503 응답 주입. store 전달은 PROCESSED, search INDEX는 QUEUED 재시도 대기 |
| ES 복구 | 재시도 가능 시각 이후 동일 작업 처리, 실제 ES 문서와 검색 복구 |
| 오래된 ES 문서 재전달 | external version 충돌을 정상 무시하고 최신 문서 유지 |
| 시작 시각 만료 | 만료 이벤트 처리 후 ES 문서·검색에서 제외 |

성공한 실행의 Oracle 스키마는 `BT_STORE_B4F36D3D215A`다. 검증 스키마는 점검용으로 남긴다. ES alias는 `event-flow-116ad4cd-8b14-4694-be74-75244ac8fe79`이며 테스트 종료 시 해당 테스트 인덱스만 삭제했다. 각 재실행은 새로운 임시 스키마와 UUID alias를 사용한다.

실행 로그: `/private/tmp/bintime-store-search-flow.log`. 실제 Oracle·ES 실행의 JUnit XML은 기본 테스트 재실행으로 덮어쓰이지 않도록 `/private/tmp/bintime-store-search-flow-results.xml`에 보존했다.

## 검증 중 수정한 문제

1. `SearchWorker.save`가 기존 제안의 publicationVersion을 갱신하지 않아 재발행 후 고객 제안이 사라졌다. 같은 제안 행을 더 높은 발행 버전으로 갱신하며, 늦은 이전 버전은 되돌리지 않도록 수정했다. 기본 SearchBehaviorTests에도 회귀 테스트를 추가했다.
2. Elasticsearch가 이전 검색 문서의 external version을 거절한 409도 일시 장애로 처리해 계속 재시도했다. 문서 upsert의 `version_conflict_engine_exception`만 정상적인 오래된 작업으로 무시하고, 다른 409와 503은 오류로 유지했다. 기본 ElasticsearchVersionTests에 구분 검증을 추가했다.

새 DB 변경은 필요하지 않다.

## 재실행

로컬 Oracle 컨테이너 `bintime-dev-oracle-1`과 로컬 ES가 실행 중이어야 한다. 저장소 루트에서 실행한다.

```bash
python3 infra/oracle/verify-store-search.py
```

다른 로컬 ES 주소를 사용하면 다음과 같이 지정한다. localhost/127.0.0.1만 허용한다.

```bash
SEARCH_ELASTICSEARCH_URL=http://localhost:9200 \
python3 infra/oracle/verify-store-search.py
```

스크립트는 기존 Oracle 검증 도구를 사용해 새 BT_STORE_ 스키마에 최신 초기 DDL과 search DDL을 적용하고 테스트에만 접속 정보를 전달한다. 비밀번호를 출력하거나 .env의 앱 계정으로 접속하지 않는다. 테스트는 사용자 지정 기준 위치 `37.51335413975466, 126.92649449142719`를 사용한다.

## 범위와 한계

Oracle·ES·이벤트 소비자·search worker·store 및 검색 HTTP는 실제 구현을 사용한다. 테스트는 스케줄 간격 대신 동일 소비자/worker 메서드를 직접 호출해 순서를 제어하며, 만료·재시도는 테스트 시계를 이동해 확인한다. 실제 프로세스 재시작·다중 인스턴스·부하는 별도 검증이다.

외부 카카오 로그인 대신 test 프로필의 명시적 로컬 식별을 사용한다. 대기 생성/조회는 실제 Spring 서비스를 호출한다. OpenAI는 1,536차원 테스트 벡터로 대체하고 실제 알림 전송은 제외한다. NOTIFY 작업의 전역 설정·알림 Gateway는 아직 미연결이므로 처리 완료로 주장하지 않는다.

`store_events.PROCESSED`는 search 큐 전달 완료이고, INDEX의 SUCCEEDED가 ES 반영 요청 완료다. 검색에서 안 보이는 것만 검사하지 않고 ES 원본 문서의 slots까지 확인해 원장 재검증이 ES 갱신 오류를 가리는 경우를 방지한다.
