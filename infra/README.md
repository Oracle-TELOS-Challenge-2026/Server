# 해커톤 개발 환경

Java 21 · Spring Boot · Gradle · Oracle · Redis를 사용한다.
기능은 `user`, `notification`, `search`, `store`, `chatting`에서 나눠 구현한다.
프로젝트 규칙은 동료가 작성한 루트 `AGENTS.md`, `README.md`를 따른다.

| 환경 | DB | Spring 프로필 |
| --- | --- | --- |
| 개인 개발 | 각 PC의 Oracle Free 26ai 컨테이너 | `local` (기본값) |
| 자동 테스트 | 매 실행 시 생성하는 메모리 H2 | `test` |
| 배포·시연 | OCI Autonomous DB | `prod` (배포 Compose에서 지정) |

세 환경은 데이터를 공유하지 않는다. 배포 DB에서 개발용 데이터 입력이나 테이블 실험을 하지 않는다.

## 팀원 시작 순서

저장소를 clone하고 IntelliJ에서 루트 `build.gradle`을 연다.
Project SDK와 Gradle JVM은 모두 Java 21로 지정한다.

```sh
git clone https://github.com/Oracle-TELOS-Challenge-2026/Server.git
cd Server
./gradlew build
```

빌드·테스트는 메모리 H2를 사용하므로 DB 비밀번호나 Docker 없이 실행할 수 있다.
실제 Oracle SQL·예약 동시성은 별도 검증이 필요하다.

각자 기능 브랜치를 만들어 작업하고 PR을 올린다. PR 본문에 목적·변경 내용·검증을 적으며 Approve는 선택이다.

```sh
git switch main
git pull --ff-only
git switch -c feat/user-login
```

## 로컬 실행

예약 제안 기능을 사용하려면 기존 DB와 새 DB 모두
[`08-chat-booking-proposals.sql`](oracle/migrations/08-chat-booking-proposals.sql)을
`004-chatting.sql` 이후에 적용해야 한다. 채팅·예약 쓰기를 멈춘 상태에서 로컬과 OCI를 각각
확인하고 실행한다. 제안 테이블과 예약의 `service_description` 컬럼을 추가하며 기존 데이터는
유지한다. 새 코드의 일반 메시지 조회도 제안 테이블을 사용하므로 **SQL 적용 후 앱을 배포**한다.
`deploy.sh`는 이 SQL을 자동 실행하지 않는다. 기존 초기 DDL·더미데이터를 다시 넣지 않는다.

Docker Desktop을 실행하고 저장소 루트에 로컬 `.env`를 만든 뒤 Oracle·Redis를 띄운다. OCI 접속 정보는 필요 없다.
Oracle 이미지와 데이터 파일을 위해 디스크 여유 공간을 확보한다. 첫 실행은 이미지 다운로드와 DB 초기화로 몇 분 걸릴 수 있다.
Oracle Lite 이미지의 ARM64·AMD64 태그와 Compose 설정을 사용한다. 로컬 컨테이너에서 앱 계정 생성과 SQL 로그인을 검증했다.

```sh
# 최초 1회. 기존 .env가 있으면 덮어쓰지 않고 필요한 키만 추가한다.
cp -n .env.example .env
docker compose -f compose.dev.yaml up --detach --wait --wait-timeout 600
```

Lite 이미지의 `startup` 스크립트(`infra/oracle/local-setup/01-app-user.sh`)가 없는 `USERS` 테이블스페이스와 `.env`의 `DB_USERNAME` 계정을 생성한다. 최초 비밀번호는 `DB_PASSWORD`다. 재시작 시 기존 계정·비밀번호·데이터는 유지한다.
기존 컨테이너에 이 설정을 반영할 때도 위 `up` 명령을 사용한다. 볼륨을 삭제할 필요는 없다.
개발 Compose는 별도의 `bintime-dev` 프로젝트와 볼륨을 사용한다. Oracle·Redis 포트는 `127.0.0.1`에만 노출한다.
`.env.example`의 예시 비밀번호는 로컬 컨테이너 전용이다. 배포 DB·Redis에는 별도 비밀번호를 사용한다.
개발 PC의 공인 IP를 OCI DB 허용 목록에 등록할 필요가 없다.

이전에 로컬에서 배포용 Compose를 실행했다면 포트 충돌을 피하도록 먼저 `docker compose down`을 실행한다. `--volumes`는 붙이지 않는다.

### IntelliJ에서 개발할 때

Run Configuration의 Working directory를 저장소 루트로 지정하고 `BintimeApplication`을 실행하거나 다음 명령을 쓴다. 기본 프로필은 `local`이다. Spring은 `application-local.properties`의 config import로 루트 `.env`를 읽는다.
기존 Run Configuration에 `SPRING_PROFILES_ACTIVE=dev/prod`, `SPRING_DATASOURCE_*`, `DB_*`, `REDIS_*` 등을 설정했다면 로컬 실행에서는 제거하거나 의도한 값으로 맞춘다. 환경 변수는 `.env`보다 우선한다.

```sh
./gradlew bootRun
```

### 환경별 설정 파일

| 파일 | 역할 |
| --- | --- |
| `application.properties` | 공통 설정과 기본 프로필 `local` |
| `application-local.properties` | 루트 `.env`를 읽고 DB·Redis 연결값에 적용 |
| `application-prod.properties` | 배포 Compose가 주입한 DB·Redis 환경 변수 사용 |
| `.env.example` | 로컬 `.env` 템플릿 |
| `.env.prod.example` | 서버 `.env` 템플릿 |

로컬 `.env`는 Spring의 Java Properties 형식과 Compose가 함께 읽으므로 따옴표, `export`, 인라인 주석, 변수 확장 없이 `KEY=value`로 작성한다. 값은 예시처럼 ASCII 영문·숫자와 `_ . - : / @ !` 범위로 작성한다.

- `ORACLE_PWD`: 로컬 Oracle 관리자 비밀번호. 앱 계정 비밀번호와 별개다.
- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: 호스트에서 실행하는 Spring의 Oracle 연결값. 계정명은 영문 대문자로 시작하는 1~30자의 대문자·숫자·밑줄이다.
- `ORACLE_PORT`: 호스트에 노출하는 Oracle 포트. 변경하면 `DB_URL`의 포트도 함께 변경한다. 서비스 이름은 `FREEPDB1`이다.
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`: 호스트에서 실행하는 Spring의 Redis 연결값. Compose는 `REDIS_PORT`로 호스트 포트를 노출한다.
- 개발 Compose는 Oracle·Redis만 실행한다. Spring은 IDE나 `bootRun`으로 호스트에서 실행하므로 호스트명은 `localhost`를 사용한다.
- 기존 Oracle 계정은 `.env`의 비밀번호를 바꿔도 자동 변경하지 않는다. 실제 계정 비밀번호와 `.env`를 함께 변경해야 한다.
- `.env`는 Git과 Docker 이미지에서 제외한다. 테스트용 `test`, 문서용 `api-docs` 프로필은 유지한다.

명시적으로 프로필을 지정하려면 `./gradlew bootRun --args='--spring.profiles.active=local'`을 사용한다. 배포는 `prod`를 지정한다.

### DataGrip

DataGrip의 개발 DB 연결은 `.env` 값에 맞춘다. 아래는 템플릿 기본값이며 기존 Autonomous DB 연결과 별도 데이터 소스로 추가한다.

| 항목 | 값 |
| --- | --- |
| JDBC URL | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| 사용자 | `BINTIME_APP` |
| 비밀번호 | `BinTimeAppLocal123` |

Redis는 `localhost:6379`, 비밀번호는 `BinTimeRedisLocal123`이다.
Spring은 `localhost:8080`에서 실행된다. 연결 확인은 `curl --fail http://localhost:8080/actuator/health/readiness`로 한다.

### IDE에서 DDL 실행

SQL 콘솔의 대상 스키마는 `PUBLIC`이 아니라 실제 앱 계정으로 선택한다. 로컬 템플릿은 `BINTIME_APP`이며 다른 DB는 해당 접속 계정을 사용한다. `ALTER SESSION SET CURRENT_SCHEMA = PUBLIC`은 실행하지 않는다. 현재 접속 계정과 스키마는 다음으로 확인한다.

```sql
SELECT SYS_CONTEXT('USERENV', 'SESSION_USER') AS login_user,
       SYS_CONTEXT('USERENV', 'CURRENT_SCHEMA') AS current_schema
FROM dual;
```

**신규 빈 스키마에는 [oracle/ddl.sql](oracle/ddl.sql) → [migrations/004-chatting.sql](oracle/migrations/004-chatting.sql) → [migrations/08-chat-booking-proposals.sql](oracle/migrations/08-chat-booking-proposals.sql) 순서로 적용한다.** 기본 도메인·search v2·사용자 프로필·FCM 구독·알림 전달·store 카탈로그/이벤트 변경과 잠금 256행은 통합본에 포함되어 있다. 통합본에 없는 채팅 v1 컬럼과 읽음·멱등성 테이블은 004로, 제안 테이블과 예약 설명 컬럼은 08로 추가한다. 이미 반영된 `local-setup/01ddl.sql`, `docs/sql/search-oracle.sql`, 다른 마이그레이션은 중복 실행하지 않는다. 생성된 앱 계정의 기본 테이블스페이스와 권한을 사용하므로 환경별 계정 준비는 먼저 수행한다. 로컬 계정 준비는 `local-setup/01-app-user.sh`, Autonomous DB 권한 준비는 `app-user-grants.sql`을 참고한다.

통합 DDL은 IDE/JDBC용 일반 Oracle SQL이며 SQL*Plus 지시문과 환경별 계정 생성은 포함하지 않는다. SQL*Plus 호출 스크립트에서는 `SET DEFINE OFF`와 `WHENEVER SQLERROR EXIT SQL.SQLCODE`를 설정한다. 기존 DB에는 적용 상태별 마이그레이션을 사용한다. 과거 분산 설치 경로는 QA 비교용으로 보존하며, 전체 반영표와 재검증 방법은 [통합 DDL 안내](../docs/unified-ddl.md)를 참고한다.

### 테이블 변경과 데이터 보존

`ddl-auto=validate`이므로 엔티티를 추가하면 로컬 DB에 적용할 SQL도 함께 관리한다.
로컬에서 먼저 검증하고 배포 때 같은 변경 SQL을 Autonomous DB에 적용한다. 로컬 데이터를 배포 DB에 덮어쓰지 않는다.
로컬 Oracle의 기본 테이블스페이스는 `USERS`, Autonomous DB 앱 계정의 테이블스페이스는 `DATA`다.
공통 테이블 생성 SQL에는 테이블스페이스를 고정하지 않는다.

개발 DB 중지는 `docker compose -f compose.dev.yaml down`이다. 볼륨은 유지되어 재실행해도 데이터가 남는다.
`down --volumes`는 로컬 Oracle·Redis 데이터를 모두 삭제하므로 초기화할 때만 사용한다.
Oracle Free와 Autonomous DB는 관리 기능·접속 방식 등이 다르므로 Oracle 고유 기능은 배포 환경에서도 확인한다.

## FCM 알림 설정

채팅·매칭 공통 발송 이력을 사용하는 새 앱 배포 전에는 기존 로컬·OCI DB의 `BINTIME_APP`에 [009-notification-push-history.sql](oracle/migrations/009-notification-push-history.sql)을 적용한다. 테이블 1개와 인덱스 2개만 추가하며 기존 데이터는 변경하지 않는다. 신규 `ddl.sql`과 `local-setup/01ddl.sql`에는 포함되어 있다. 기록 상태와 조회 SQL은 [발송 이력 안내](../docs/push-history.md)를 참고한다.

기존 DB에는 `002-notification-subscriptions.sql`, `003-notification-delivery.sql`을 순서대로 적용한다. 파일은 `infra/oracle/migrations/`에 있으며 재실행할 수 있다. 기존 Web Push 구독은 비활성화되므로 프런트에서 로그인 세션으로 FID를 다시 등록해야 한다.

기본값 `FCM_ENABLED=false`에서는 외부 전송을 하지 않고 매칭 알림의 DB 발송 대기열을 보존한다. 채팅 푸시는 비활성화 중 생긴 요청을 보관·재생하지 않는다. 로컬에서 실제 전송을 켤 때 `.env`의 `FCM_PROJECT_ID`, `GOOGLE_APPLICATION_CREDENTIALS`(저장소 밖 서비스 계정 JSON의 절대 경로), `FCM_ENABLED=true`를 설정한다. Windows 경로는 `/`를 사용하고 Java Properties가 읽는 비 ASCII 문자는 Unicode escape로 작성한다. 키 파일은 Git이나 이미지에 포함하지 않는다.

배포용 선택 설정은 `compose.fcm.yaml`이다. 서버 `.env`에 `FCM_CREDENTIALS_FILE`을 서버에 별도로 보관한 JSON의 절대 경로로 지정하고, `FCM_PROJECT_ID`와 강한 `NOTIFICATION_INTERNAL_TOKEN`을 설정한다. 키는 읽기 전용 Compose secret으로 앱에 전달한다. N-06 HTTP 호출은 이 내부 토큰을 요구하며 같은 앱의 검색 이벤트는 공개 Java 계약으로 연결된다.

```sh
docker compose -f compose.yaml -f compose.fcm.yaml up --detach --no-build --wait
```

`deploy.sh`는 `compose.fcm.yaml`도 서버에 전송한다. 서버 `.env`의 `FCM_CREDENTIALS_FILE`이 비어 있지 않으면 이 선택 파일을 자동으로 포함해 FCM을 활성화하고, 없거나 빈 값이면 기본 Compose만 사용한다. 서비스 계정 JSON과 서버 환경변수는 스크립트가 전송하지 않으므로 별도로 준비해야 한다. JSON은 앱 사용자 UID `10001`이 읽을 수 있어야 한다. 키나 프로젝트 설정만으로 전송을 검증할 수는 없으며 로그인한 브라우저에서 등록한 FID가 필요하다. 테스트는 대체 FCM 서버를 사용한다.

알림은 현재 앱 프로세스의 유효한 로그인 세션에 연결된 FID만 대상으로 한다. 로그아웃·세션 만료·앱 재시작 후에는 브라우저가 다시 등록해야 한다. FCM 수락은 사용자 화면 표시나 수신 완료를 뜻하지 않는다. 전송 결과가 불명확한 네트워크 장애 후 재시도에서는 외부 전송을 정확히 한 번으로 보장할 수 없으며, 알림 저장은 사용자·슬롯별로 중복 방지한다.

채팅 푸시도 기존 FCM 설정과 N-04 FID 등록을 사용한다. 추가 환경변수나 Oracle 마이그레이션은 없다. 채팅 메시지 커밋 후 상대방에게 보내며 발신자·읽은 메시지·해제된 구독은 제외한다. 채팅 발송 대기·재시도는 단일 앱의 제한된 메모리 작업이므로 재시작 시 미전송 푸시는 유실될 수 있다. 매칭의 DB 발송 대기열과 구분하며 자세한 데이터·프론트 연결은 [채팅 FCM 안내](../docs/chatting.md#채팅-fcm-푸시)를 따른다.

## CI와 수동 배포

- CI는 PR과 `main` 푸시에서 `./gradlew build --no-daemon`만 실행한다.
- Docker 이미지 빌드·업로드·자동 배포는 CI에 넣지 않는다.
- 앱 이미지 이름은 `bintime-backend:local`로 고정하고 레지스트리에서 pull하지 않는다.
- 서버는 `opc@140.238.19.165`, Oracle Linux 9 / ARM64, 실행 폴더는 `~/bintime`이다.

배포용 파일은 `compose.yaml`이며 로컬 개발용 `compose.dev.yaml`과 섞어서 사용하지 않는다.
서버 `.env`에는 `.env.prod.example`을 참고해 OCI JDBC URL 전체, `BINTIME_APP` 비밀번호, 별도 Redis 비밀번호와 `ELASTIC_PASSWORD`를 설정한다. 운영 Compose는 인증된 Elasticsearch도 실행하며, 앱은 비공개 Docker 네트워크의 `http://elasticsearch:9200`으로 접속한다. 자세한 실행·인증 설정은 [Search 운영 안내](../docs/search.md#docker-실행과-운영-es-연결)를 참고한다.
`$`, `#`, 공백이 들어간 값은 작은따옴표로 감싼다. 배포용 Compose는 `prod` 프로필을 명시하고 환경 변수가 없으면 실행을 중단한다.
Spring의 `prod` 프로필은 로컬 `.env`를 직접 읽지 않는다. 서버에서는 `compose.yaml`이 `.env`를 읽어 app 컨테이너에 필요한 변수를 주입한다. 직접 JAR로 실행할 경우 같은 값을 프로세스 환경 변수로 제공한다.
`.env`는 Git·Docker 이미지에 포함하지 않는다.

필요할 때 저장소 루트에서 `bash deploy.sh`를 실행한다. Docker Desktop과 Java 21이 필요하다.
스크립트는 Docker와 병합 충돌 확인, 서버 인증서 존재 확인, 빌드·테스트, ARM64 이미지와 인프라 설정 전송, 자동 갱신 타이머 활성화, 서버 설정에 따른 FCM 선택, Compose 설정 검증, 컨테이너 실행, Nginx 검증·리로드, 내부·외부 HTTPS 상태 확인을 수행한다. 호출한 위치와 관계없이 스크립트가 있는 저장소에서 빌드한다. 이미지에 현재 커밋의 `org.opencontainers.image.revision`을 기록하며 미커밋 변경이 있으면 `-dirty`를 붙인다. 전송한 이미지 ID·revision과 실행 중인 앱 컨테이너가 다르면 성공 처리하지 않는다. 실제 배포 없이 스크립트 흐름을 확인하려면 `python3 infra/test-deploy.py`를 실행한다.
DB 마이그레이션과 Elasticsearch 최초 색인은 자동 실행하지 않는다. 앱 쓰기를 중지하고 백업한 뒤 해당 환경에 필요한 SQL을 먼저 적용한다. search v1 환경은 [v2 전환 절차](../docs/search-schema-v2.md), store는 migration `06`·`07`, FCM은 `002`·`003`, 채팅은 [채팅 DB 안내](../docs/chatting-db.md)를 확인한다. 최초 ES 인덱스/alias 생성은 [초기 색인 안내](../docs/search.md#store-카탈로그-연결-및-초기-색인)에 따라 별도 수행한다.
최초 인증서 발급을 마친 서버를 대상으로 한다. 인증서가 없으면 설정을 덮어쓰기 전에 중단한다.
SSH 키 기본 경로는 `~/Downloads/team3/team-3-1.key`이며 다른 PC에서는 `BINTIME_SSH_KEY=/키/경로 bash deploy.sh`로 지정한다.
Git 푸시로 자동 배포되지는 않는다. 아래는 같은 과정을 개별 명령으로 실행하는 예다.
아래 수동 Compose 예시는 FCM을 사용하지 않는 경우다. FCM 운영 설정이 있으면 `docker compose -f compose.yaml -f compose.fcm.yaml` 조합을 사용한다.

```sh
./gradlew build
docker build --platform linux/arm64 --tag bintime-backend:local .
docker save bintime-backend:local | ssh -i ~/Downloads/team3/team-3-1.key opc@140.238.19.165 'sudo docker load'
ssh -i ~/Downloads/team3/team-3-1.key opc@140.238.19.165 'cd ~/bintime && sudo docker compose up --detach --no-build --wait --wait-timeout 180'
```

Compose나 Nginx 설정을 바꿨다면 해당 설정 파일도 서버의 같은 경로에 복사한다.
서버 `.env`는 별도 관리하고 Git·이미지에 넣지 않는다.
Redis 데이터는 Docker 볼륨에 보존되며 `docker compose down --volumes`는 데이터를 삭제한다.

## 서버 확인

서버 `.env`의 `HTTP_BIND_ADDRESS=0.0.0.0`, `HTTP_PORT=8080`으로 Nginx를 공개한다.
서버 방화벽과 OCI 보안 규칙에서 TCP 80·443·8080 접속을 허용해야 한다. 80은 인증서 검증과 HTTPS 리다이렉트에 사용하고 기존 8080 주소도 HTTPS로 리다이렉트한다. Redis는 계속 `127.0.0.1:6379`에만 바인딩하고 Spring은 Docker 내부에서만 접근한다.

최초 인증서 발급 및 HTTPS 전환을 완료한 뒤 사용하는 주소는 다음과 같다.

- API 주소: `https://140.238.19.165`
- 상태 확인: `https://140.238.19.165/healthz`
- Swagger: `https://140.238.19.165/swagger-ui/index.html`

Nginx가 `http://127.0.0.1:5173`, `http://localhost:5173`, `https://frontend-oracle.vercel.app`에 대한 CORS와 OPTIONS 사전 요청을 처리한다. 5173은 프론트 개발 PC의 포트이므로 API 서버에 열지 않는다. 이 설정은 Nginx를 거치는 배포 API에 적용된다.
프론트 API 주소도 위 HTTPS 주소로 지정한다. HTTP 주소로 보내는 OPTIONS 요청은 리다이렉트가 CORS 오류로 보일 수 있다.

### IP 인증서와 자동 갱신

Let’s Encrypt의 `shortlived` IP 인증서는 유효기간이 6일이다. `infra/certbot/bintime-cert-renew.timer`가 하루 두 번 갱신 필요 여부를 확인하며, `renew.sh`가 공식 `certbot/certbot:v5.8.0` 이미지를 실행하고 Nginx를 검증·리로드한다.
인증서·개인키·ACME 계정은 서버 `~/bintime/infra/certbot/conf`에만 보관한다. `conf`, `www`, `log` 디렉터리는 Git에서 제외하며 Docker 앱 이미지에도 포함되지 않는다.
인증서 최초 발급에는 Let’s Encrypt 이용약관 동의와 IP 소유 검증이 필요하다. 서버의 공개 IP가 바뀌면 새 IP 인증서를 발급하고 Nginx·배포 스크립트의 주소도 함께 변경한다.

```sh
# 서버에서 갱신 예약과 실행 기록을 확인한다.
sudo systemctl list-timers bintime-cert-renew.timer
sudo journalctl -u bintime-cert-renew.service --no-pager -n 30
```

외부 상태 확인은 `curl --fail https://140.238.19.165/healthz`로 한다. 필요하면 HTTPS SSH 터널도 사용할 수 있다.

```sh
ssh -i ~/Downloads/team3/team-3-1.key -N -L 18443:127.0.0.1:443 opc@140.238.19.165
```

다른 터미널에서 `curl --fail --connect-to 140.238.19.165:443:127.0.0.1:18443 https://140.238.19.165/healthz`를 실행한다. 인증서 검증을 끄는 `-k`는 사용하지 않는다.

## 맞춤 빈자리 알림 배포

기존 DB는 ADMIN 콘솔에서 [008-personalized-notifications.sql](oracle/migrations/008-personalized-notifications.sql) 전체를 하나의 PL/SQL 문장으로 **새 앱 배포 전에** 실행한다. 대상 스키마는 백엔드 계정 `BINTIME_APP`으로 명시되어 있다. user_accounts에 기본 비동의 값과 동의 시각을 추가하고 notification_alerts.waitlist_id의 NOT NULL을 해제한다. 기존 데이터는 유지하며 재실행 가능하다. DataGrip에 그대로 붙여 넣을 수 있도록 SET/WHENEVER와 마지막 `/`는 포함하지 않는다. 별도 COMMIT 없이 Oracle DDL이 자동 커밋된다. 새 통합 ddl.sql에는 포함되어 있다. 현재 시연의 발송 판정은 기존 recommendation_opt_in=N/Y를 사용하지 않는다. DB 값 변경 없이 FID 등록으로 기존 빈자리 탐색을 접수한다([현재 안내](../docs/user-profile.md#맞춤-빈자리-알림-동의-u-10u-11)).

`SEARCH_INDEX_REBUILD=false`, `SEARCH_SCHEDULING_ENABLED=true`, `BINTIME_STORE_EVENT_DELIVERY_ENABLED=true`의 정상 실행 구성을 사용하고 OpenAI/FCM 설정 및 현재 로그인 세션 FID를 준비한다. 이 시연 경로에는 프로필 위치 등록이 필요 없다. 빈자리 발행 후 비동기 매칭·푸시 제출로 진행하며 전체 ES 재색인은 필요 없다.

## 빈자리 즉시확정·사장님 선택

이 기능이 포함된 새 앱은 배포 전에 [010-vacancy-booking-approval.sql](oracle/migrations/010-vacancy-booking-approval.sql)을 ADMIN에서 BINTIME_APP에 적용해야 한다. 앱 쓰기를 중지하고 백업 후 실행한다. 기존 데이터는 보존하고 기본 정책은 즉시확정이다. 운영 반영 여부와 프런트 선행 작업은 [예약 방식 안내](../docs/vacancy-booking-approval.md)를 확인한다. `deploy.sh`는 이 SQL을 자동 적용하지 않는다.
