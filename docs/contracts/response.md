# 공통 응답 계약

모든 `/api/v1/**` JSON 응답은 다음 상속 구조를 사용한다.

```text
BaseResponse<T>
├── ApiResponse<T>       # SUCCESS, success.data에 도메인 DTO
└── ApiErrorResponse     # FAILURE, error에 오류 정보
```

도메인의 응답 DTO는 success.data에 들어가는 데이터다. 공통 봉투를 도메인 데이터 안에 중복 상속하지 않는다.

## 성공

```json
{
  "resultType": "SUCCESS",
  "success": { "data": { "id": "usr_001" } },
  "error": null,
  "meta": {
    "timestamp": "2026-07-12T07:00:00",
    "path": "/api/v1/users/me"
  }
}
```

목록의 items/page/counts 등 도메인 구조는 success.data 안에서 유지한다. 데이터 없는 일반 성공은 success.data=null이다. null 필드도 JSON에서 생략하지 않는다.

Controller는 `ResponseEntity<ApiResponse<UserProfileResponse>>`처럼 성공 스키마를 명시하고 `ApiResponse.success(dto)`를 반환할 수 있다. 공통 DTO를 사용하지 않고 일반 DTO를 반환해도 `ApiResponseAdvice`가 자동 wrapping한다. 이미 감싼 응답은 중복 wrapping하지 않는다.

## 실패

```json
{
  "resultType": "FAILURE",
  "success": null,
  "error": {
    "code": "NOT_IMPLEMENTED",
    "message": "Not Implemented",
    "details": [],
    "retryable": false
  },
  "meta": {
    "timestamp": "2026-07-12T07:00:00",
    "path": "/api/v1/users/me"
  }
}
```

`ApiExceptionHandler`가 입력 바인딩·형식 오류, 명시적 HTTP 예외, 예상하지 못한 예외를 공통 실패 응답으로 변환한다. HTTP 상태는 유지한다. 내부 예외 메시지는 그대로 노출하지 않는다. 도메인 오류가 구현되면 `ApiErrorResponse`의 code/message/details로 표현한다.

## 메타데이터와 예외

- meta에는 timestamp와 path만 있다. requestId/serverTime은 제거했다.
- timestamp는 서버에서 생성한 Asia/Seoul 시각이며 `yyyy-MM-dd'T'HH:mm:ss` 형식이다.
- path는 쿼리 문자열을 제외한 요청 경로다. 이미 공통 응답에 담긴 메타도 실제 요청값으로 채운다.
- 204·205·3xx는 기존 HTTP 본문 규약을 유지한다. JSON 봉투를 위해 성공 상태를 200으로 바꾸지 않는다.
- SSE와 WebSocket은 프레임을 유지한다. 스트림 시작 전 HTTP 오류는 공통 실패 응답이다.
- Swagger·Actuator·정적 리소스에는 이 API 봉투를 적용하지 않는다.
- 이 변경은 사용자가 요청한 새로운 응답 계약이며 기존 Notion v0.1의 data/meta 봉투보다 우선한다. 각 도메인의 요청·응답 데이터 필드는 그대로 유지한다.

## Swagger 응답 표시

- 성공 응답은 각 Controller에 선언한 상태와 반환 DTO 스키마로 표시한다. JSON 성공 응답에는 `resultType: SUCCESS`, `success.data`의 API별 DTO, `error: null`, `meta`를 포함한 `success` 예시를 제공한다. `success` 필드는 객체 스키마와 null 허용 여부를 함께 표시하여 내부 DTO 구조가 보이도록 한다. 204·리다이렉트에는 본문이 없고 SSE는 `text/event-stream`을 사용한다.
- 오류 상태를 모든 API에 일괄 추가하지 않는다. 전역 예외 처리기의 응답도 자동 추가하지 않으며 각 API의 `@ApiResponse`에 명시한 오류만 공통 `ApiErrorResponse` 스키마로 표시한다. 오류별 설명은 유지한다.
- 구현한 사용자 API(U-01~U-09)는 실제 상태를 명시하고, notification·chatting의 미구현 계약 골격은 `501`을 명시한다. 프로필·선호도의 `409 VERSION_CONFLICT`와 위치의 `409 STALE_LOCATION`은 실제 오류로 처리한다. U-08 선호 저장은 구현되어 있으나 search의 벡터 변경 이벤트 경로와는 아직 연결되지 않았다. 미구현 도메인의 충돌·스트림 오류는 해당 기능 구현 시 연결한다.
- 문서에 공통 오류를 나열하지 않아도 입력 바인딩 실패나 예상하지 못한 서버 오류는 기존 예외 처리기에 따라 공통 실패 응답으로 반환한다.
