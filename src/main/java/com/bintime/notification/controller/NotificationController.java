package com.bintime.notification.controller;

import com.bintime.notification.dto.MarkNotificationReadRequest;
import com.bintime.notification.dto.NotificationListResponse;
import com.bintime.notification.dto.NotificationResponse;
import com.bintime.notification.service.NotificationService;
import com.bintime.notification.stream.NotificationStream;
import com.bintime.user.security.BintimePrincipal;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Tag(name = "notification")
public class NotificationController {

  private final ObjectProvider<NotificationService> notifications;
  private final ObjectProvider<NotificationStream> streams;

  public NotificationController(
      ObjectProvider<NotificationService> notifications,
      ObjectProvider<NotificationStream> streams) {
    this.notifications = notifications;
    this.streams = streams;
  }

  private NotificationService notifications() {
    var service = notifications.getIfAvailable();
    if (service == null)
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    return service;
  }

  @Operation(
      operationId = "getNotifications",
      summary = "N-01 · 알림 목록·안 읽은 수 조회",
      description =
          "createdAt 내림차순. 본인 알림만. 클릭 후 ST-05 최신 확인.\n\n"
              + "- 로그인 세션 — 본인 데이터\n\n"
              + "본인의 저장된 알림과 최신 슬롯 가용성을 조회합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481649c12da127fb36943"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "알림 목록과 안 읽은 수",
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
                                "items": [
                                  {
                                    "id": "noti_001",
                                    "type": "VACANCY_MATCHED",
                                    "title": "원하던 시간에 빈자리가 생겼어요",
                                    "body": "네일 모먼트 · 10월 17일 16:00 · 35,000원",
                                    "target": {
                                      "storeId": "shop_moment",
                                      "slotId": "slot_17_1600",
                                      "publicationVersion": 2,
                                      "waitlistIds": [
                                        "wait_001"
                                      ]
                                    },
                                    "action": {
                                      "type": "OPEN_SLOT",
                                      "path": "/customer/shops/shop_moment?slotId=slot_17_1600"
                                    },
                                    "availability": "AVAILABLE",
                                    "readAt": null,
                                    "createdAt": "2026-10-16T14:20:00+09:00"
                                  }
                                ],
                                "page": {
                                  "nextCursor": null,
                                  "hasNext": false
                                },
                                "unreadCount": 1
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/notifications"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "유효하지 않은 커서 또는 limit", content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content)
  @GetMapping(value = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<NotificationListResponse>>
      getNotifications(
          @AuthenticationPrincipal BintimePrincipal principal,
          @RequestParam(value = "unreadOnly", required = false, defaultValue = "false")
              Boolean unreadOnly,
          @RequestParam(value = "cursor", required = false) String cursor,
          @io.swagger.v3.oas.annotations.Parameter(
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
              @RequestParam(value = "limit", required = false, defaultValue = "20")
              Integer limit) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            notifications().list(principal, Boolean.TRUE.equals(unreadOnly), cursor, limit)));
  }

  @Operation(
      operationId = "markNotificationRead",
      summary = "N-02 · 알림 읽음 처리",
      description =
          "반복 요청은 최초 readAt 유지. 읽음과 예약 완료는 별개.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "본인 알림만 변경하며 다른 탭에 notification.read 이벤트를 전달합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48110a228da3a4f12e85a"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "읽음 처리된 알림",
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
                                "id": "noti_001",
                                "type": "VACANCY_MATCHED",
                                "title": "원하던 시간에 빈자리가 생겼어요",
                                "body": "네일 모먼트 · 10월 17일 16:00 · 35,000원",
                                "target": {
                                  "storeId": "shop_moment",
                                  "slotId": "slot_17_1600",
                                  "publicationVersion": 2,
                                  "waitlistIds": [
                                    "wait_001"
                                  ]
                                },
                                "action": {
                                  "type": "OPEN_SLOT",
                                  "path": "/customer/shops/shop_moment?slotId=slot_17_1600"
                                },
                                "availability": "AVAILABLE",
                                "readAt": "2026-10-16T14:20:00+09:00",
                                "createdAt": "2026-10-16T14:20:00+09:00"
                              }
                            },
                            "error": null,
                            "meta": {
                              "timestamp": "2026-10-16T14:20:00",
                              "path": "/api/v1/notifications/{notificationId}/read"
                            }
                          }
                          """)))
  @ApiResponse(responseCode = "400", description = "read=true 필수", content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content)
  @ApiResponse(responseCode = "403", description = "CSRF 검증 실패", content = @Content)
  @ApiResponse(responseCode = "404", description = "본인 알림 없음", content = @Content)
  @PatchMapping(
      value = "/api/v1/notifications/{notificationId}/read",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<NotificationResponse>>
      markNotificationRead(
          @AuthenticationPrincipal BintimePrincipal principal,
          @PathVariable("notificationId") String notificationId,
          @RequestHeader(value = "X-CSRF-Token", required = true) String csrfToken,
          @RequestBody MarkNotificationReadRequest request) {
    return ResponseEntity.ok(
        com.bintime.common.dto.ApiResponse.success(
            notifications().read(principal, notificationId, request.read())));
  }

  @Operation(
      operationId = "streamNotifications",
      summary = "N-03 · 실시간 알림 수신 (SSE)",
      description =
          "세션 쿠키 인증. Last-Event-ID로 누락 재생. 커서 만료는 410. 15초 heartbeat. 저장 알림 ID로 중복 제거.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- Accept: text/event-stream; Last-Event-ID 선택\n\n"
              + "최초 연결은 현재 이후 이벤트부터 전달하며 로그아웃·세션 만료 시 종료합니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481529f3ff4711b7c4aaf"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(
      responseCode = "200",
      description = "SSE 스트림. Last-Event-ID가 afterEventId보다 우선. 24시간 재생, 15초 heartbeat",
      content =
          @Content(
              mediaType = "text/event-stream",
              schema = @Schema(type = "string"),
              examples =
                  @ExampleObject(
                      value =
                          "200 text/event-stream\n"
                              + "id: evt_user_000013\n"
                              + "event: notification.created\n"
                              + "data:"
                              + " {\"notification\":{\"id\":\"noti_001\",\"type\":\"VACANCY_MATCHED\",\"title\":\"원하던"
                              + " 시간에 빈자리가 생겼어요\",\"body\":\"네일 모먼트 · 10월 17일 16:00 ·"
                              + " 35,000원\",\"target\":{\"storeId\":\"shop_moment\",\"slotId\":\"slot_17_1600\",\"publicationVersion\":2,\"waitlistIds\":[\"wait_001\"]},\"action\":{\"type\":\"OPEN_SLOT\",\"path\":\"/customer/shops/shop_moment?slotId=slot_17_1600\"},\"availability\":\"AVAILABLE\",\"readAt\":null,\"createdAt\":\"2026-10-16T14:20:00+09:00\"}}\n\n"
                              + ": heartbeat\n")))
  @ApiResponse(
      responseCode = "410",
      description = "STREAM_CURSOR_EXPIRED: 새 연결 후 알림함 재조회",
      content = @Content)
  @ApiResponse(responseCode = "400", description = "본인 이벤트 커서가 아님", content = @Content)
  @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content)
  @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> streamNotifications(
      @AuthenticationPrincipal BintimePrincipal principal,
      HttpServletRequest request,
      @RequestParam(value = "afterEventId", required = false) String afterEventId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    var stream = streams.getIfAvailable();
    if (stream == null)
      throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
    return ResponseEntity.ok()
        .header("Cache-Control", "no-cache")
        .header("X-Accel-Buffering", "no")
        .body(stream.open(principal, request.getSession(false), lastEventId, afterEventId));
  }
}
