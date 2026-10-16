# Store → Search 조회 계약

`StoreSearchCatalogService`가 기존 `SearchStoreCatalog`의 5개 메서드를 구현한다. 별도 HTTP API는 추가하지 않는다. search는 store 엔티티나 테이블을 직접 조회하지 않고 이 공개 서비스를 호출한다.

## 조회 규칙

- `validate`: 매장·시술의 존재, 소속·카테고리 일치, 시술 활성 여부를 확인한다. 현재 슬롯이 없어도 예약 대기는 허용한다. serviceId만 있으면 해당 시술의 매장으로 검증한다.
- `available`: AVAILABLE, 활성 시술, 미래 시작, 유효한 시작/종료 구간, 확정 예약과 겹치지 않는 공급 중 지정 매장·시술·카테고리·최대 가격·거리·전체 시술 시간 조건을 만족하는 슬롯을 반환한다. 거리 탈락이 많아도 내부 페이지를 끝까지 순회한다. 키워드는 기존 search ConditionRules와 동일하게 이 필수 조건 필터에서 추가 제한하지 않는다.
- `revalidate`: 원장에서 다시 확인하고 예약·철회(CLOSED)·차단·미발행(DRAFT)·만료·비활성·없는 슬롯을 제외한다. 슬롯 시작 시각부터 제외하며 만료 스케줄러 저장 전에도 적용한다. DB 행은 삭제하지 않는다. 중복 ID는 합치고 Oracle IN 절은 500개씩 나눠 조회한다.
- `describe`: 매장·시술 표시 정보를 반환한다. 지정된 참조가 없으면 404, 소속 불일치는 400이다. 기존 대기의 표시를 위해 비활성 시술 이름도 조회할 수 있다. 둘 다 미지정이면 두 참조 모두 null이다.
- `export`: 매장 ID 순으로 전체 문서를 조회한다. 빈 매장도 포함한다. description은 매장명·카테고리·소개·전체 시술명(ID 순)으로 구성하며 슬롯·시간·가격 문자열은 넣지 않는다. slots에는 현재 가용 공급만 담는다. 가격은 슬롯 등록/발행 당시 스냅샷이다.

페이지 크기는 1~100이다. available은 시작 시각·ID 오름차순, export는 매장 ID 오름차순이며 마지막 반환 항목으로 다음 커서를 만든다. 커서는 작업 종류와 정규화한 조건에 묶이므로 다른 조건·작업에서 재사용하면 400 INVALID_CURSOR다. 한 번의 전체 순회가 DB 고정 스냅샷은 아니므로 순회 중 추가·변경은 이후 재색인/이벤트에서 보완한다. 예약 직전 store의 최종 검증도 유지한다.

실패 코드는 기존 공통 오류 봉투를 사용한다. 400에는 잘못된 입력·커서·매장/시술 카테고리·소속 불일치, 404에는 STORE_NOT_FOUND/SERVICE_NOT_FOUND, 409에는 SERVICE_INACTIVE가 해당한다. 일반 available에서 특정 시술을 지정하지 않으면 비활성 시술의 슬롯은 조용히 제외한다.

## 검색 문서 버전

`store_shops.search_version`은 1에서 시작하는 검색 전용 버전이다. 기존 `version_no`와 API의 expectedVersion은 변경하지 않는다.

매장 수정, 시술 생성·수정, 일반 슬롯 생성, 예약, 취소, 완료·노쇼, 빈자리 발행·철회·재발행, 저장 상태 만료 시 매장 잠금 아래 증가한다. 같은 업무 트랜잭션 안에서 갱신하므로 실패 시 롤백되고 멱등 응답 재생 시에는 증가하지 않는다. 여러 슬롯이 한 번에 만료되면 여러 번 증가할 수 있으므로 버전은 연속값이 아닌 단조 증가값으로 사용한다. 직접 SQL로 원장을 수정하면 이 규칙이 자동 실행되지 않는다.

export는 REQUIRES_NEW + SERIALIZABLE 읽기 트랜잭션으로 커밋된 매장·시술·공급과 버전을 같은 스냅샷에서 읽는다. 시간만 지나도 조회에서는 만료 공급이 빠질 수 있지만 저장 버전은 만료 작업이 상태를 갱신할 때 증가한다. search는 시간 필터와 최종 revalidate를 계속 적용해야 한다.

이 변경은 이벤트를 전송하지 않는다. `store_events` 소비, 재시도·오래된 이벤트 무시, ES 증분 갱신 연결은 STORE-NEXT-04에서 구현한다.

## DB 적용

- 신규 빈 DB: 최신 `infra/oracle/local-setup/01ddl.sql`에 컬럼·기본값·CHECK가 포함되어 있다.
- 기존 store API DB: 앱 쓰기를 중지하고 백업 후 `infra/oracle/migrations/06-store-search-catalog.sql`을 앱 스키마에서 실행한다. 재실행 가능하며 기존 데이터와 version_no는 보존한다.
- store 구현 이전 DB: 필요한 02 변경을 먼저 적용한 뒤 06을 적용한다. 번호순으로 모든 도메인 마이그레이션을 무조건 실행하지 않는다.
- 자동 적용은 없고 ddl-auto=validate를 유지한다. 이번 작업에서는 앱용 BINTIME_APP 및 운영 DB에 적용하지 않았다.

```sql
SELECT column_name, data_default, nullable
FROM user_tab_columns
WHERE table_name='STORE_SHOPS' AND column_name='SEARCH_VERSION';

SELECT COUNT(*) AS invalid_search_versions
FROM store_shops WHERE search_version IS NULL OR search_version < 1;
```

## 검증

StoreApiTests에서 실제 등록·예약·취소·발행·철회·재발행·만료 후 원장을 재조회한다. 참조 검증, 같은 시작 시각의 커서 순회, 필터 변경 거절, 가격·전체 시간·거리, 원시 100행 이후 적격 공급, 1,000개 초과 ID 재검증, 빈 매장·전체 시술 설명·검색 버전·롤백·멱등·경합을 검증한다.

`infra/oracle/verify-store.py`는 기존 스키마 변경과 신규 스키마 양쪽에서 같은 테스트를 실행하고 06 재실행도 확인한다. 검증용 BT_STORE_ 스키마만 생성하며 앱 DB를 변경하지 않는다. 실제 search 사용자 인증·알림·ES 전체 흐름은 별도 QA다.

2026-10-16 최종 검증: `./gradlew format test build --no-daemon` 성공. 전체 154건 중 151건 통과, 외부 연동 3건 제외. 격리 Oracle의 기존 변경·신규 스키마 양쪽에서도 StoreApiTests 22건이 통과했다. 실제 search 대기 등록·상세 조회, 검색 버전의 경합·멱등 재생·DB 실패 롤백까지 확인했다. BINTIME_APP과 운영 DB에는 06을 적용하지 않았다.

## Search 연결 확인

2026-10-16 search 정상 프로필이 실제 카탈로그 Bean을 사용하도록 연결을 확인했다. api-docs에서만 대체 Bean을 사용한다. `StoreCatalogConnectionTests`는 격리 Oracle+실제 ES로 매장/시술/슬롯 등록, 대기 참조 조회, 초기 색인, S-01 HTTP 조회, 예약 후 원장 재검증과 FULFILLED를 확인했다. OpenAI는 대역을 사용했다.

로컬 `BINTIME_APP`에는 06을 적용했고 현재 이벤트 엔티티 기동을 위해 07도 적용했다. 기존 20개 테이블의 행 수를 유지했다. 정상 서버 기동과 초기 alias 생성·공개 검색 HTTP 200을 확인했으며 앱 DB의 매장/슬롯 0건 상태도 유지했다. 이전 절의 앱 DB 미적용 기록은 구현 당시의 기록이다. 운영 DB에는 별도 적용이 필요하다. 초기 색인 실행 옵션은 [Search 운영 문서](search.md#store-카탈로그-연결-및-초기-색인)를 참고한다.
