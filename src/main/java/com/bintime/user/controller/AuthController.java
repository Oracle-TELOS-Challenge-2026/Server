package com.bintime.user.controller;

import com.bintime.user.dto.CsrfTokenResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "user")
public class AuthController {

  // OAuth·로그아웃 요청은 Spring Security 필터가 먼저 처리한다. 여기서는 계약을 노출한다.5
  @Operation(
      operationId = "startKakaoLogin",
      summary = "U-01 · 카카오 로그인 시작",
      description =
          "returnTo는 허용된 앱 상대 경로만 수락. state를 생성하여 로그인 요청과 연계한다.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Spring Security가 302 리다이렉트한다. 카카오 키 미설정 시 503, 허용하지 않은 returnTo는 400.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481bf8e32fb1a1d26405a"))
  @ApiResponse(
      responseCode = "302",
      description = "302 Found — 응답 본문 없음. Location: 카카오 OAuth 로그인 URL",
      content = @Content)
  @ApiResponse(responseCode = "400", description = "허용하지 않은 returnTo 경로", content = @Content)
  @ApiResponse(responseCode = "503", description = "카카오 로그인 키 미설정", content = @Content)
  @GetMapping(value = "/api/v1/auth/oauth/kakao/start")
  public ResponseEntity<Void> startKakaoLogin(
      @RequestParam(value = "returnTo", required = false) String returnTo) {
    throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Operation(
      operationId = "handleKakaoCallback",
      summary = "U-02 · 카카오 로그인 콜백",
      description =
          "code·state 검증 후 최초 사용자는 생성한다. 공급자 토큰은 브라우저로 반환하지 않는다.\n\n"
              + "- 공개 API (OAuth 콜백은 state 검증)\n\n"
              + "Spring Security가 state·인가 코드를 검증하고 HttpOnly 세션 쿠키를 발급한다. "
              + "동의 거절·만료·잘못된 state는 /login?error=OAUTH_FAILED로 이동한다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48139a62bc467e95200eb"))
  @ApiResponse(
      responseCode = "303",
      description =
          "303 See Other — 응답 본문 없음. Set-Cookie: bt_session=...; Location: 허용된 앱 경로. 실패 시"
              + " /login?error=OAUTH_FAILED",
      content = @Content)
  @GetMapping(value = "/api/v1/auth/oauth/kakao/callback")
  public ResponseEntity<Void> handleKakaoCallback(
      @RequestParam(value = "code", required = true) String code,
      @RequestParam(value = "state", required = true) String state) {
    throw new org.springframework.web.server.ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE);
  }

  @Operation(
      operationId = "logout",
      summary = "U-03 · 로그아웃",
      description =
          "현재 세션과 쿠키를 폐기한다. 실시간 연결 종료 연동은 해당 도메인 구현 시 추가한다.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "X-CSRF-Token은 GET /api/v1/auth/csrf에서 조회한다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481ebbafccffba89f42cf"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content)
  @ApiResponse(responseCode = "403", description = "CSRF 토큰 없음 또는 불일치", content = @Content)
  @PostMapping(value = "/api/v1/auth/logout")
  public ResponseEntity<Void> logout(
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "getCsrfToken",
      summary = "U-09 · CSRF 토큰 조회",
      description =
          "로그인 세션과 연결된 CSRF 토큰을 발급한다.\n\n- 로그인 세션 — 본인 데이터\n\n"
              + "상태 변경 요청의 X-CSRF-Token에 담는다. expiresAt은 현재 요청 기준 세션의 유휴 만료 시각이다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48152b85ac51b50ad6c18"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "로그인 세션의 CSRF 토큰",
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
  @ApiResponse(responseCode = "401", description = "로그인 세션 없음", content = @Content)
  @GetMapping(value = "/api/v1/auth/csrf", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<CsrfTokenResponse>> getCsrfToken(
      HttpServletRequest request) {
    var session = request.getSession(false);
    var token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    if (session == null || token == null) {
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    OffsetDateTime expiresAt =
        session.getMaxInactiveInterval() <= 0
            ? null
            : OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(session.getMaxInactiveInterval());
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            new CsrfTokenResponse(token.getToken(), expiresAt)));
  }
}
