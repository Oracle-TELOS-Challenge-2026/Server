# 빈틈 (BinTime) Backend

**갑작스럽게 생긴 예약의 빈틈을, 필요한 사람에게 연결하다.**

빈틈은 네일숍 등 예약 기반 매장의 취소 슬롯과 고객의 예약 수요를 연결하는 실시간 예약 매칭 서비스다. 고객이 원하는 시간과 매장을 반복해서 검색하는 대신, 예약 조건을 미리 등록하면 조건에 맞는 빈자리가 생겼을 때 알림을 받는다.

## 핵심 기능

- **AI 예약 조건 분석:** 자연어에서 시간·지역·서비스·예산을 추출하고, 사용자 확인 후 예약 대기를 활성화한다.
- **예약 실패 기반 대기:** 빈틈 내부에서 특정 매장 예약에 실패하면 해당 매장 취소 알림을 등록한다.
- **취소 슬롯 등록:** 매장 관리자가 실제 예약 가능한 취소 시간과 가격을 직접 등록한다.
- **개인화 매칭:** 필수 조건을 먼저 검증한 후 벡터 유사도 등으로 고객의 우선순위를 산정한다.
- **실시간 알림과 예약:** 상위 5명에게 알림을 보내고 최초 유효 요청의 예약을 확정한다. 동시 요청에도 한 슬롯은 한 고객만 예약할 수 있다.
- **매장 채팅:** 고객과 매장 사장님이 WebSocket으로 대화한다. 예약 조건을 분석하는 AI 대화와는 역할을 구분한다.

초기 MVP는 네일숍과 결제 없는 예약 확정에 집중한다. 외부 예약 시스템 자동 연동은 이후 확장 범위다.

## 도메인 구성

| 도메인 | 책임 | 소유 데이터 |
| --- | --- | --- |
| `user` | 로그인, 사용자 프로필, 현재/설정 위치 저장, 알림 수신 동의 관리 | 사용자, 위치, 계정 설정 |
| `notification` | 예약 알림 생성·실시간 전달, 중복 및 발송 제한, 전달 상태 관리 | 알림, 발송 이력 |
| `search` | 자연어 예약 의도 분석, OpenAI API를 통한 사용자별 벡터 생성, 예약 대기 조건 관리, 벡터 검색 및 고객 매칭 | 예약 의도, 명시적 검색 선호, 사용자별 벡터, 매칭 결과, 검색 인덱스 |
| `store` | 매장·시술 관리, 타임테이블, 취소 슬롯 발행, 예약 선점·확정·만료 | 매장, 시술, 예약 슬롯, 예약 |
| `chatting` | 고객과 매장 사장님의 WebSocket 채팅, 참여자 권한 확인, 메시지 저장·전달 | 채팅방, 참여자, 메시지 |

예약 수요인 `ReservationIntent`는 `search`, 실제 공급인 `ReservationSlot`과 최종 `Reservation`은 `store`가 소유한다. 검색 결과가 있더라도 예약 가능 여부는 `store`에서 다시 검증한다.

도메인은 다른 도메인의 Repository나 테이블에 직접 접근하지 않고 공개 유스케이스 또는 이벤트로 협력한다. 단일 애플리케이션 내에서는 공개 서비스 메서드로 협력하고, 외부 연동이나 구현 분리가 필요한 경계에는 Port/Adapter를 적용한다.

## 예정 기술 스택

아래는 현재 적용 중이거나 도입 예정인 기술과 역할이다. 빌드 설정은 Spring Boot 4.1.1과 Java 21을 사용한다.

| 기술 | 예정 용도 |
| --- | --- |
| Spring / Spring Boot | 백엔드 애플리케이션, API, 도메인 유스케이스 |
| Redis | 캐시, 실시간 전달 및 비동기 이벤트 처리 보조. Pub/Sub·Streams 등의 사용 경계는 구현 시 결정 |
| Elasticsearch | 매장·예약 조건 검색 및 임베딩 벡터 검색용 인덱스 |
| Autonomous DB | 사용자·예약·채팅 등 영속 데이터 저장 후보. 정확한 제품과 연결 방식은 확인 필요 |
| Nginx | 리버스 프록시와 실시간 연결 라우팅 |
| Docker | 애플리케이션 및 인프라 실행 환경 컨테이너화 |
| OpenAI API | 자연어 예약 조건 분석과 사용자별 선호 벡터 생성. 사용할 모델과 벡터 차원은 미정 |

Elasticsearch는 검색용 파생 인덱스로 사용하고, 예약 확정 여부의 원본 저장소로 사용하지 않는다. Redis의 캐시나 전달 상태만으로 최종 예약을 확정하지 않는다.

## 사용자별 벡터 생성

`search`가 사용자의 명시적 선호와 확인된 예약 조건을 텍스트로 구성해 OpenAI API에 전달하고, 반환된 벡터를 사용자 ID와 연결해 Elasticsearch에 인덱싱한다. 선호나 조건이 변경되면 벡터를 갱신한다. 시간·거리·가격 등 필수 조건은 벡터 유사도와 별도로 검증한다.

벡터 생성은 자체 모델을 배포하는 대신 OpenAI의 Embeddings API를 호출하는 방식이다. 자연어 조건 추출과 벡터 생성은 별도 API 작업으로 구분한다. 참고: [OpenAI 공식 벡터 생성 문서](https://developers.openai.com/api/docs/guides/embeddings).

## 아키텍처

**전통적인 Spring MVC 계층 구조를 기본으로 하고, 외부 연동이 필요한 부분에 Port/Adapter 인터페이스를 적용한다.** 하나의 Spring 애플리케이션 안에서 다섯 도메인을 패키지로 분리한다.

```text
Controller → Service → Repository → DB
                 ↓ 의존
           Port (인터페이스)
                 ↑ 구현
           Adapter → OpenAI API / Elasticsearch / Redis / 외부 API
```

- `controller`: HTTP 요청과 응답을 처리한다. WebSocket 핸들러도 전송 계층에서 요청을 받아 서비스로 전달한다.
- `service`: 비즈니스 흐름, 검증, 트랜잭션을 관리한다. Repository와 필요한 Port를 호출한다.
- `repository`: 영속 데이터 접근을 담당한다. 일반적인 DB 접근은 Spring Data Repository 등을 사용하며 별도 Port로 중복 추상화하지 않는다.
- `entity`: 영속 엔티티와 상태·도메인 규칙을 표현한다. 필요에 따라 JPA 매핑을 함께 사용한다.
- `dto`: 요청·응답 데이터를 정의한다.
- `port`: 서비스가 필요로 하는 외부 기능의 인터페이스를 정의한다. 예를 들어 사용자 벡터 생성, 벡터 검색, 알림 전달을 표현한다.
- `adapter`: Port를 구현하고 외부 기술의 SDK, 통신 방식, 응답 변환을 처리한다.

서비스는 외부 연동 시 구체적인 Adapter 대신 Port 인터페이스에 의존하고, Spring에서 구현체를 주입한다. 예를 들어 `search`의 서비스가 `UserVectorPort`를 호출하면 OpenAI API를 호출하는 `OpenAiUserVectorAdapter`가 이를 구현한다.

Controller마다 입력 Port를 만들거나 모든 Service에 인터페이스와 구현 클래스를 나눌 필요는 없다. 도메인 모델과 영속 엔티티의 일괄 분리도 강제하지 않는다. 비즈니스 규칙은 Controller나 WebSocket 핸들러에 집중시키지 않는다.

## 파일 구조

현재는 Spring 부트스트랩과 도메인별 Controller·DTO 계약 골격이 있다. Service·Repository·Port·Adapter는 실제 기능을 구현할 때 필요한 범위만 추가한다.

```text
src/main/java/com/bintime/
├── BintimeApplication.java
├── common/             # Swagger 설정, 공통 응답·오류 봉투
├── user/
├── notification/
├── search/
├── store/
└── chatting/
```

각 도메인의 현재 구조와 향후 추가할 패키지는 다음과 같다.

```text
<domain>/
├── controller/     # 현재: HTTP 매핑, Swagger 문서, 501 스텁
├── dto/            # 현재: 요청·응답 및 실시간 프레임 계약
├── service/        # 추후 구현
├── repository/     # 추후 구현
├── entity/         # 추후 구현
├── port/           # 외부 기능 인터페이스가 필요할 때 추가
└── adapter/        # 해당 Port의 외부 연동 구현
```

패키지 루트는 `com.bintime`이며 Gradle을 사용한다. 인프라 실행 설정은 [infra/README.md](infra/README.md)를 참고한다.

## API 계약 및 Swagger

[Notion 통합 API 명세](https://false-resolution-beb.notion.site/34c9cc5a61234f3d92bb3c6bd58a0df4)를 기준으로 REST·SSE 45개와 채팅 WebSocket 프레임을 정의했다. 도메인별로 Controller·DTO 이름과 요청·응답 스키마를 맞췄으며 실제 처리 로직은 아직 없다.

- [API별 Controller·DTO 이름 매핑](docs/contracts/api.md)
- [SSE·WebSocket 계약](docs/contracts/realtime.md)
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- 외부 API OpenAPI: `/v3/api-docs/public` (43개 operation)
- 내부 계약 OpenAPI: `/v3/api-docs/internal` (2개 operation)

DB·Redis 없이 문서를 확인하려면 Java 21 이상 환경에서 다음 명령을 실행한다. Gradle toolchain은 Java 21을 사용한다.

```bash
./gradlew bootRun --args='--spring.profiles.active=api-docs'
```

현재 Spring Security 기본 보안을 유지한다. 문서 접근 시 사용자명 `user`와 실행 로그의 임시 생성 비밀번호로 로그인한다. 이는 문서 열람용 기본 인증이며 명세의 카카오 로그인·세션 인증 구현이 아니다. `api-docs` 프로필은 문서 검토용이며 Swagger의 API 실행 기능은 비활성화한다.

Controller에 도달한 유효한 요청은 본문 없는 `501 Not Implemented`를 반환한다. 문서의 200·201 등은 향후 구현할 성공 계약이다. Spring Security와 요청 바인딩은 그 전에 요청을 거절할 수 있다. 인증·권한·CSRF 검증, 비즈니스 검증, 예약, AI 호출, SSE/WS 전송은 구현하지 않았다.

Notion v0.1의 알림 대상·슬롯 상태 정책은 기존 기획과 차이가 있어 [계약 문서](docs/contracts/api.md)에 기록했다. 이번 변경은 해당 정책을 실행하지 않는다.

## 예약 연결 흐름

1. `user`가 인증과 사용자 위치·설정을 제공한다.
2. `search`가 OpenAI API로 자연어를 분석하고 사용자 확인 후 예약 대기 조건을 저장한다. 사용자가 명시한 선호와 예약 조건을 바탕으로 사용자별 벡터를 생성·갱신한다.
3. `store`가 취소 슬롯을 저장하고 매칭을 위한 슬롯 생성 사실을 전달한다.
4. `search`가 시간·위치·서비스·가격 조건을 필터링하고 고객 순위를 계산한다.
5. `notification`이 대상자의 활성 조건과 동의 여부를 확인해 중복 없이 알림을 전달한다.
6. 고객의 예약 요청을 `store`가 원자적으로 처리하고 예약 결과와 슬롯 마감을 관련 도메인에 전달한다.

사용자 확인으로 대기 조건이 활성화되는 시점에도 기존의 이용 가능한 슬롯을 탐색한다. `chatting`의 메시지 송수신은 예약 확정과 별도 유스케이스로 처리한다.

## 실시간 알림 설계 기준

`notification`은 **낮은 지연 시간**이 중요한 도메인이다. 구체적인 지연 목표는 부하 조건과 함께 정하고 측정한다.

- OpenAI API를 통한 자연어 분석과 사용자 벡터 생성은 조건 등록·변경 시 수행하고, 슬롯 발생 시에는 저장된 벡터를 활용해 API 호출을 불필요하게 반복하지 않는다.
- 예약 트랜잭션이 AI 호출이나 클라이언트 알림 전송 완료를 기다리지 않도록 처리 경계를 분리한다. 커밋된 슬롯이 매칭으로 전달되는 과정의 유실 방지도 함께 설계한다.
- 실시간 전달 채널과 재시도·복구 수단을 구분한다. Redis 사용만으로 전달 보장이나 초저지연 달성을 가정하지 않는다.
- 동일 사용자·슬롯의 중복 알림을 방지하고, 연결이 끊긴 사용자는 저장된 알림을 다시 조회할 수 있게 한다.
- 슬롯 등록→매칭 완료→발송→클라이언트 수신 구간의 지연과 실패율을 측정한다. 필요 시 p95/p99 지연과 연결 수를 함께 확인한다.
- 통합 API 명세의 알림 채널은 SSE이며, 비접속 사용자는 동의한 웹 푸시로 보완한다. 사장님과의 채팅 채널은 WebSocket을 사용한다.

## 개발 및 검증

작업 전 [AGENTS.md](AGENTS.md)를 확인한다.

```bash
./gradlew format
./gradlew test
./gradlew build
```

`format`은 API 골격의 Java 코드를 포맷한다. 기존 부트스트랩 파일은 대상에서 제외한다. 계약 테스트는 Notion에서 조회한 요청·응답 예시와 전체 경로를 Swagger에 대조하고 DTO 역직렬화와 501 스텁을 검증한다. 테스트는 H2와 문서용 프로필을 사용하며 외부 AI·DB·Redis 연결이 필요하지 않다.
