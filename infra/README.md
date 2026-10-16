# 해커톤 개발 환경

Java 21 · Spring Boot · Gradle · Oracle Autonomous DB · Redis를 사용한다.
기능은 `user`, `notification`, `search`, `store`, `chatting`에서 나눠 구현한다.
프로젝트 규칙은 동료가 작성한 루트 `AGENTS.md`, `README.md`를 따른다.

## 팀원 시작 순서

기본 설정이 `main`에 병합된 뒤 저장소를 clone하고 IntelliJ에서 루트 `build.gradle`을 연다.
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

1. `.env.example`을 `.env`로 복사한다. 기존 `.env`는 덮어쓰지 않는다.
2. 담당자에게 DB URL과 `BINTIME_APP` 계정 비밀번호를 별도로 받는다. `.env`는 Git에 올라가지 않는다.
3. `REDIS_PASSWORD`는 로컬용으로 정한다. 예: `openssl rand -hex 32`.

```sh
cp -n .env.example .env
chmod 600 .env
```

`DB_URL`에는 DataGrip에서 성공한 TLS JDBC URL 전체를 넣는다. `$`, `#`, 공백이 들어간 값은 작은따옴표로 감싼다.
DB에 직접 연결할 개발 PC의 송신 공인 IP는 OCI DB의 허용 목록에 추가해야 한다.
`BINTIME_APP`은 이미 생성되어 있다. 새 DB나 계정을 팀원마다 만들 필요는 없다.

### IntelliJ에서 개발할 때

`.env`를 채운 뒤 Redis만 실행한다.

```sh
docker compose up --detach redis
```

IntelliJ의 Run Configuration → Environment variables에 `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_PASSWORD`를 설정하고 `BintimeApplication`을 실행한다.
Redis 기본 주소는 `localhost:6379`다. Spring을 IDE에서 실행하면 `.env`를 자동으로 읽지 않는다.

### 전체를 Docker로 실행할 때

```sh
./gradlew build
docker compose build app
docker compose up --detach --no-build --wait --wait-timeout 180
curl --fail http://127.0.0.1:8080/healthz
```

Spring·Redis 연결이 정상이면 `{"status":"UP"}`을 반환한다.
Nginx는 `127.0.0.1:8080`, Redis는 `127.0.0.1:6379`에서만 호스트에 연결된다.
공유 DB의 테이블은 자동 생성하지 않으므로 엔티티를 추가할 때 적용 SQL도 함께 관리한다.

## CI와 수동 배포

- CI는 PR과 `main` 푸시에서 `./gradlew build --no-daemon`만 실행한다.
- Docker 이미지 빌드·업로드·자동 배포는 CI에 넣지 않는다.
- 앱 이미지 이름은 `bintime-backend:local`로 고정하고 레지스트리에서 pull하지 않는다.
- 서버는 `opc@140.238.19.165`, Oracle Linux 9 / ARM64, 실행 폴더는 `~/bintime`이다.

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
