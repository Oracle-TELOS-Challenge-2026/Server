# 해커톤 개발 환경

Java 21 · Spring Boot · Gradle · Oracle · Redis를 사용한다.
기능은 `user`, `notification`, `search`, `store`, `chatting`에서 나눠 구현한다.
프로젝트 규칙은 동료가 작성한 루트 `AGENTS.md`, `README.md`를 따른다.

| 환경 | DB | Spring 프로필 |
| --- | --- | --- |
| 개인 개발 | 각 PC의 Oracle Free 26ai 컨테이너 | `dev` (기본값) |
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

Docker Desktop을 실행하고 개발용 Oracle·Redis를 띄운다. `.env`나 OCI 접속 정보는 필요 없다.
Oracle 이미지와 데이터 파일을 위해 디스크 여유 공간을 확보한다. 첫 실행은 이미지 다운로드와 DB 초기화로 몇 분 걸릴 수 있다.
Oracle Lite 이미지의 ARM64·AMD64 태그와 Compose 설정은 확인했다. 현재 담당자 PC의 디스크 공간 부족으로 로컬 Oracle 초기화·앱 연결은 아직 검증하지 못했다.

```sh
docker compose -f compose.dev.yaml up --detach --wait --wait-timeout 600
```

`BINTIME_APP` 계정은 최초 DB 볼륨 생성 시 자동으로 만들어진다.
개발 Compose는 별도의 `bintime-dev` 프로젝트와 볼륨을 사용한다. Oracle·Redis 포트는 `127.0.0.1`에만 노출한다.
저장소에 있는 고정 비밀번호는 로컬 컨테이너 전용이다. 배포 DB·Redis에는 별도 비밀번호를 사용한다.
개발 PC의 공인 IP를 OCI DB 허용 목록에 등록할 필요가 없다.

이전에 로컬에서 배포용 Compose를 실행했다면 포트 충돌을 피하도록 먼저 `docker compose down`을 실행한다. `--volumes`는 붙이지 않는다.

### IntelliJ에서 개발할 때

추가 환경 변수 없이 `BintimeApplication`을 실행하거나 다음 명령을 쓴다. 기본 프로필은 `dev`다.
기존 Run Configuration에 `SPRING_PROFILES_ACTIVE=prod`, `SPRING_DATASOURCE_*` 등을 설정했다면 로컬 실행에서는 제거한다.

```sh
./gradlew bootRun
```

DataGrip의 개발 DB 연결은 다음과 같다. 기존 Autonomous DB 연결과 별도 데이터 소스로 추가한다.

| 항목 | 값 |
| --- | --- |
| JDBC URL | `jdbc:oracle:thin:@//localhost:1521/FREEPDB1` |
| 사용자 | `BINTIME_APP` |
| 비밀번호 | `BinTimeAppLocal123` |

Redis는 `localhost:6379`, 비밀번호는 `BinTimeRedisLocal123`이다.
Spring은 `localhost:8080`에서 실행된다. 연결 확인은 `curl --fail http://localhost:8080/actuator/health/readiness`로 한다.

### 테이블 변경과 데이터 보존

`ddl-auto=validate`이므로 엔티티를 추가하면 로컬 DB에 적용할 SQL도 함께 관리한다.
로컬에서 먼저 검증하고 배포 때 같은 변경 SQL을 Autonomous DB에 적용한다. 로컬 데이터를 배포 DB에 덮어쓰지 않는다.
로컬 Oracle의 기본 테이블스페이스는 `USERS`, Autonomous DB 앱 계정의 테이블스페이스는 `DATA`다.
공통 테이블 생성 SQL에는 테이블스페이스를 고정하지 않는다.

개발 DB 중지는 `docker compose -f compose.dev.yaml down`이다. 볼륨은 유지되어 재실행해도 데이터가 남는다.
`down --volumes`는 로컬 Oracle·Redis 데이터를 모두 삭제하므로 초기화할 때만 사용한다.
Oracle Free와 Autonomous DB는 관리 기능·접속 방식 등이 다르므로 Oracle 고유 기능은 배포 환경에서도 확인한다.

## CI와 수동 배포

- CI는 PR과 `main` 푸시에서 `./gradlew build --no-daemon`만 실행한다.
- Docker 이미지 빌드·업로드·자동 배포는 CI에 넣지 않는다.
- 앱 이미지 이름은 `bintime-backend:local`로 고정하고 레지스트리에서 pull하지 않는다.
- 서버는 `opc@140.238.19.165`, Oracle Linux 9 / ARM64, 실행 폴더는 `~/bintime`이다.

배포용 파일은 `compose.yaml`이며 로컬 개발용 `compose.dev.yaml`과 섞어서 사용하지 않는다.
서버 `.env`에는 `.env.example`을 참고해 OCI JDBC URL 전체, `BINTIME_APP` 비밀번호, 별도 Redis 비밀번호를 설정한다.
`$`, `#`, 공백이 들어간 값은 작은따옴표로 감싼다. 배포용 Compose는 `prod` 프로필을 명시하고 환경 변수가 없으면 실행을 중단한다.
`.env`는 Git·Docker 이미지에 포함하지 않는다.

필요할 때만 ARM64 이미지를 빌드해서 SSH로 직접 전달한다.

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

외부 도메인과 HTTPS는 아직 설정하지 않았다. Mac에서 SSH 터널을 열어 확인한다.

```sh
ssh -i ~/Downloads/team3/team-3-1.key -N -L 18080:127.0.0.1:8080 opc@140.238.19.165
```

다른 터미널에서 `curl --fail http://127.0.0.1:18080/healthz`를 실행한다.
