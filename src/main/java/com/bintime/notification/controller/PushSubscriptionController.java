package com.bintime.notification.controller;

import com.bintime.notification.dto.PushSubscriptionResponse;
import com.bintime.notification.dto.RegisterPushSubscriptionRequest;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "notification")
public class PushSubscriptionController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "registerPushSubscription",
      summary = "N-04 · 웹 푸시 구독 등록",
      description =
          "동일 계정+endpoint upsert. 푸시 권한은 브라우저 명시 동의. 다른 계정 연결은 409 SUBSCRIPTION_IN_USE.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48156a1d6f67cc3cbd034"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "201", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(
      responseCode = "200",
      description = "기존 리소스 또는 동일 요청 재사용",
      useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/notifications/push-subscriptions",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<PushSubscriptionResponse>>
      registerPushSubscription(
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestBody RegisterPushSubscriptionRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "deletePushSubscription",
      summary = "N-05 · 웹 푸시 구독 해제",
      description =
          "본인 구독만 해제. 이미 해제된 구독도 204.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4814491f0f94e752b9a50"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @DeleteMapping(value = "/api/v1/notifications/push-subscriptions/{subscriptionId}")
  public ResponseEntity<Void> deletePushSubscription(
      @PathVariable("subscriptionId") String subscriptionId,
      @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
