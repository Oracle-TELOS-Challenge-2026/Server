# Search SQL 파일

신규 **전체 앱 DB**는 [통합 DDL](../../infra/oracle/ddl.sql) 하나로 생성한다. 반영 내역과 Oracle 검증 결과는 [통합 DDL 안내](../unified-ddl.md)를 참고한다.

| 파일 | 역할 |
| --- | --- |
| `search-oracle.sql` | search v2 신규 생성 SQL. 기존 QA·테스트가 참조하는 경로 |
| `search-oracle-v2.sql` | v2 명시 별칭. 현재 위 파일과 내용이 동일 |
| `search-oracle-v1.sql` | 이전 11개 테이블의 역사적 스냅샷. 신규 설치용이 아님 |

최신 search는 7개 테이블과 잠금 버킷 256행을 사용한다. 기존 v1 DB 전환은 [전환 안내](../search-schema-v2.md)의 마이그레이션 04·05를 따른다. 통합 DDL을 실행한 DB에 이 디렉터리 SQL을 추가 실행하지 않는다.
