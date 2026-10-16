package com.bintime.user.controller;

import com.bintime.user.dto.CsrfTokenResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "user")
public class AuthController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "startKakaoLogin",
      summary = "U-01 · 카카오 로그인 시작",
      description =
          "returnTo는 허용된 앱 상대 경로만 수락. state를 생성하여 로그인 요청과 연계한다.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481bf8e32fb1a1d26405a"))
  @ApiResponse(
      responseCode = "302",
      description = "302 Found — 응답 본문 없음. Location: 카카오 OAuth 로그인 URL",
      content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/auth/oauth/kakao/start")
  public ResponseEntity<Void> startKakaoLogin(
      @RequestParam(value = "returnTo", required = false) String returnTo) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "handleKakaoCallback",
      summary = "U-02 · 카카오 로그인 콜백",
      description =
          "code·state 검증 후 최초 사용자는 생성한다. 공급자 토큰은 브라우저로 반환하지 않는다.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48139a62bc467e95200eb"))
  @ApiResponse(
      responseCode = "303",
      description =
          "303 See Other — 응답 본문 없음. Set-Cookie: bt_session=...; Location: 허용된 앱 경로. 실패 시"
              + " /login?error=OAUTH_FAILED",
      content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/auth/oauth/kakao/callback")
  public ResponseEntity<Void> handleKakaoCallback(
      @RequestParam(value = "code", required = true) String code,
      @RequestParam(value = "state", required = true) String state) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "logout",
      summary = "U-03 · 로그아웃",
      description =
          "현재 세션·쿠키와 연결된 실시간 접속을 폐기한다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ebbafccffba89f42cf"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(value = "/api/v1/auth/logout")
  public ResponseEntity<Void> logout(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "getCsrfToken",
      summary = "U-09 · CSRF 토큰 조회",
      description =
          "로그인 세션과 연결된 CSRF 토큰을 발급한다.\n\n- 로그인 세션 — 본인 데이터\n\n현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48152b85ac51b50ad6c18"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "명세상 성공 응답 (미구현)",
      useReturnTypeSchema = true,
      content =
          @Content(
              mediaType = "application/json",
              examples =
                  @ExampleObject(
                      name = "success",
                      value =
                          """
                          {
                            "resultType": "SUCCESS",
                            "success": {
                              "data": {
                                "csrfToken": "csrf-example",
                                "expiresAt": "2026-10-23T14:20:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/auth/csrf"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/auth/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CsrfTokenResponse>> getCsrfToken() {

    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
