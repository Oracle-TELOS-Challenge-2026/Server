package com.bintime.notification.controller;

import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.dto.DispatchNotificationResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "notification-internal")
public class InternalNotificationController {

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "dispatchNotification",
      summary = "N-06 · 알림 저장·전달 의뢰 (내부)",
      description =
          "내부 인증. eventId 및 userId/slotId/publicationVersion/type로 중복 방지. 무효 후보는 200"
              + " \\{notificationId:null,status:SKIPPED\\}. 일반 수락은 202.\n\n"
              + "- INTERNAL — 외부 접근 불가, 내부 인증 또는 모듈 호출\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48197a045fff04c9ff16e"))
  @ApiResponse(
      responseCode = "202",
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PostMapping(
      value = "/api/v1/internal/notifications/dispatch",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<DispatchNotificationResponse>>
      dispatchNotification(@RequestBody DispatchNotificationRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
