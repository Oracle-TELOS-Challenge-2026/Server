package com.bintime.notification.controller;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.dto.DispatchNotificationResponse;
import com.bintime.notification.service.NotificationService;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "notification-internal")
public class InternalNotificationController {

  private final ObjectProvider<NotificationService> notifications;
  private final String internalToken;

  public InternalNotificationController(
      ObjectProvider<NotificationService> notifications,
      @Value("${notification.internal-token:}") String internalToken) {
    this.notifications = notifications;
    this.internalToken = internalToken;
  }

  @Operation(
      operationId = "dispatchNotification",
      summary = "N-06 · 알림 저장·전달 의뢰 (내부)",
      description =
          "X-Internal-Token 내부 인증. userId+slotId로 중복 방지. 무효 후보는 200"
              + " \\{notificationId:null,status:SKIPPED\\}. 일반 수락은 202.\n\n"
              + "- INTERNAL — 외부 접근 불가, 내부 인증 또는 모듈 호출\n\n"
              + "활성 동의·대기 버전·슬롯·세션 구독과 사용자/매장·시술 벡터의 코사인 유사도 기준을 확인합니다. "
              + "벡터 미준비는 503으로 보류하고 기준 미달은 SKIPPED입니다. 실제 푸시 직전에도 재검증합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48197a045fff04c9ff16e"))
  @ApiResponse(
      responseCode = "202",
      description = "영속 발송 대기열 접수. FCM 수신 완료를 의미하지 않음",
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
                                "notificationId": "noti_001",
                                "status": "QUEUED"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/internal/notifications/dispatch"
                            }
                          }
                          """)))
  @ApiResponse(
      responseCode = "200",
      description = "유효하지 않은 후보는 SKIPPED, notificationId=null",
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
                                "notificationId": null,
                                "status": "SKIPPED"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/internal/notifications/dispatch"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "유효하지 않은 발송 후보", content = @Content)
  @ApiResponse(responseCode = "403", description = "내부 토큰 없음 또는 불일치", content = @Content)
  @ApiResponse(responseCode = "404", description = "수신자 없음", content = @Content)
  @ApiResponse(responseCode = "503", description = "후보 재검증 의존 기능 장애", content = @Content)
  @PostMapping(
      value = "/api/v1/internal/notifications/dispatch",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<DispatchNotificationResponse>>
      dispatchNotification(
          @RequestHeader(value = "X-Internal-Token", required = false) String suppliedToken,
          @RequestBody DispatchNotificationRequest request) {
    ApiBusinessException.require(
        !internalToken.isBlank()
            && suppliedToken != null
            && MessageDigest.isEqual(
                internalToken.getBytes(StandardCharsets.UTF_8),
                suppliedToken.getBytes(StandardCharsets.UTF_8)),
        403,
        "INTERNAL_AUTH_REQUIRED");
    var service = notifications.getIfAvailable();
    if (service == null)
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    var result = service.enqueue(request);
    return ResponseEntity.status("QUEUED".equals(result.status()) ? 202 : 200)
        .body(com.bintime.common.dto.ApiResponse.success(result));
  }
}
