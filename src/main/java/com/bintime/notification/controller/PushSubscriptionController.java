package com.bintime.notification.controller;

import com.bintime.notification.dto.PushSubscriptionResponse;
import com.bintime.notification.dto.RegisterPushSubscriptionRequest;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.security.BintimePrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "notification")
public class PushSubscriptionController {

  private final ObjectProvider<NotificationSubscriptions> subscriptions;

  public PushSubscriptionController(ObjectProvider<NotificationSubscriptions> subscriptions) {
    this.subscriptions = subscriptions;
  }

  private NotificationSubscriptions service() {
    NotificationSubscriptions service = subscriptions.getIfAvailable();
    if (service == null)
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    return service;
  }

  @Operation(
      operationId = "registerPushSubscription",
      summary = "N-04 · FCM 설치 ID 등록",
      description =
          "동일 계정+FID 재등록은 기존 ID와 생성 시각을 유지. 다른 계정의 활성 FID는 409 SUBSCRIPTION_IN_USE. "
              + "현재 로그인 세션의 FID만 교체하고 다른 세션은 유지. 등록 성공은 발송·수신 완료를 의미하지 않음.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수. 만료 시각을 알 수 없어 expiresAt은 null.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48156a1d6f67cc3cbd034"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "201",
      description = "새 계정·FID 연결 생성",
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
                                "id": "push_001",
                                "createdAt": "2026-10-16T14:20:00+09:00",
                                "expiresAt": null
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/notifications/push-subscriptions"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "200",
      description = "기존 리소스 또는 동일 요청 재사용",
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
                                "id": "push_001",
                                "createdAt": "2026-10-16T14:20:00+09:00",
                                "expiresAt": null
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/notifications/push-subscriptions"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "409",
      description = "SUBSCRIPTION_IN_USE: 다른 계정의 활성 FID",
      content = @Content)
  @ApiResponse(
      responseCode = "400",
      description = "INVALID_INSTALLATION_ID: 잘못된 FID",
      content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 없음 또는 만료", content = @Content)
  @ApiResponse(responseCode = "403", description = "CSRF_INVALID", content = @Content)
  @PostMapping(
      value = "/api/v1/notifications/push-subscriptions",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<PushSubscriptionResponse>>
      registerPushSubscription(
          @AuthenticationPrincipal BintimePrincipal principal,
          @RequestHeader(value = "X-CSRF-Token") String csrfToken,
          @RequestBody RegisterPushSubscriptionRequest request,
          HttpServletRequest http) {
    var registration =
        service().register(principal, http.getSession(false), request.installationId());
    return ResponseEntity.status(registration.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(com.bintime.common.dto.ApiResponse.success(registration.subscription()));
  }

  @Operation(
      operationId = "deletePushSubscription",
      summary = "N-05 · FCM 설치 ID 연결 해제",
      description =
          "구독 ID로 본인의 FID 연결 전체를 해제. 이미 해제됐거나 없는 ID는 204, 타인의 활성 ID는 404. "
              + "다른 FID는 유지. 이미 FCM에 제출된 알림은 회수하지 않음.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd4814491f0f94e752b9a50"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "204", description = "204 No Content — 응답 본문 없음", content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 없음 또는 만료", content = @Content)
  @ApiResponse(responseCode = "403", description = "CSRF_INVALID", content = @Content)
  @ApiResponse(responseCode = "404", description = "NOT_FOUND: 타인의 활성 구독", content = @Content)
  @DeleteMapping(value = "/api/v1/notifications/push-subscriptions/{subscriptionId}")
  public ResponseEntity<Void> deletePushSubscription(
      @AuthenticationPrincipal BintimePrincipal principal,
      @PathVariable("subscriptionId") String subscriptionId,
      @RequestHeader(value = "X-CSRF-Token") String csrfToken) {
    service().delete(principal, subscriptionId);
    return ResponseEntity.noContent().build();
  }
}
