package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(
    oneOf = {
      NotificationStreamPayload.Created.class,
      NotificationStreamPayload.Read.class,
      NotificationStreamPayload.SessionExpired.class
    },
    description = "SSE data 필드. id/event는 SSE 전송 필드이며 JSON 봉투에 포함하지 않음")
public sealed interface NotificationStreamPayload {
  @Schema(name = "NotificationCreatedEvent", description = "notification.created")
  record Created(NotificationResponse notification) implements NotificationStreamPayload {}

  @Schema(name = "NotificationReadEvent", description = "notification.read")
  record Read(String notificationId, OffsetDateTime readAt) implements NotificationStreamPayload {}

  @Schema(name = "NotificationSessionExpiredEvent", description = "session.expired 후 스트림 종료")
  record SessionExpired(@Schema(allowableValues = "SESSION_EXPIRED") String reason)
      implements NotificationStreamPayload {}
}
