package com.bintime.notification.controller;

import com.bintime.notification.dto.MarkNotificationReadRequest;
import com.bintime.notification.dto.NotificationListResponse;
import com.bintime.notification.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.ExternalDocumentation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

  // 실제 처리 전까지 성공으로 오인되지 않도록 모든 요청에 501을 반환한다.
  @Operation(
      operationId = "getNotifications",
      summary = "N-01 · 알림 목록·안 읽은 수 조회",
      description =
          "createdAt 내림차순. 본인 알림만. 클릭 후 ST-05 최신 확인.\n\n"
              + "- 로그인 세션 — 본인 데이터\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd481649c12da127fb36943"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/notifications", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<NotificationListResponse>>
      getNotifications(
          @RequestParam(value = "unreadOnly", required = false, defaultValue = "false")
              Boolean unreadOnly,
          @RequestParam(value = "cursor", required = false) String cursor,
          @io.swagger.v3.oas.annotations.Parameter(
                  schema =
                      @io.swagger.v3.oas.annotations.media.Schema(minimum = "1", maximum = "100"))
              @RequestParam(value = "limit", required = false, defaultValue = "20")
              Integer limit) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "markNotificationRead",
      summary = "N-02 · 알림 읽음 처리",
      description =
          "반복 요청은 최초 readAt 유지. 읽음과 예약 완료는 별개.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- X-CSRF-Token 필수\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
      externalDocs =
          @ExternalDocumentation(url = "https://app.notion.com/p/3fbdaacb8cd48110a228da3a4f12e85a"))
  @SecurityRequirement(name = "sessionCookie")
  @ApiResponse(responseCode = "200", description = "명세상 성공 응답 (미구현)", useReturnTypeSchema = true)
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @PatchMapping(
      value = "/api/v1/notifications/{notificationId}/read",
      produces = MediaType.APPLICATION_JSON_VALUE,
      consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<com.bintime.common.dto.ApiResponse<NotificationResponse>>
      markNotificationRead(
          @PathVariable("notificationId") String notificationId,
          @RequestHeader(value = "X-CSRF-Token", required = false) String csrfToken,
          @RequestBody MarkNotificationReadRequest request) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }

  @Operation(
      operationId = "streamNotifications",
      summary = "N-03 · 실시간 알림 수신 (SSE)",
      description =
          "세션 쿠키 인증. Last-Event-ID로 누락 재생. 커서 만료는 410. 15초 heartbeat. 저장 알림 ID로 중복 제거.\n\n"
              + "- 로그인 세션 — 본인 데이터\n"
              + "- Accept: text/event-stream; Last-Event-ID 선택\n\n"
              + "현재는 계약 정의 단계이며 비즈니스 로직은 미구현(501)입니다.",
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
  @ApiResponse(responseCode = "501", description = "계약 골격만 정의되어 아직 구현되지 않음", content = @Content)
  @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public ResponseEntity<SseEmitter> streamNotifications(
      @RequestParam(value = "afterEventId", required = false) String afterEventId,
      @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
    throw new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_IMPLEMENTED);
  }
}
