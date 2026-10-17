package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "NotificationResponse")
public record NotificationResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "noti_001")
        String id,
    @Schema(
            description = "type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VACANCY_MATCHED",
            allowableValues = {"VACANCY_MATCHED"})
        String type,
    @Schema(
            description = "title",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "원하던 시간에 빈자리가 생겼어요")
        String title,
    @Schema(
            description = "body",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "네일 모먼트 · 10월 17일 16:00 · 35,000원")
        String body,
    @Schema(description = "target", requiredMode = Schema.RequiredMode.REQUIRED) Target target,
    @Schema(description = "action", requiredMode = Schema.RequiredMode.REQUIRED) Action action,
    @Schema(
            description = "availability",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AVAILABLE",
            allowableValues = {"AVAILABLE", "UNAVAILABLE", "UNKNOWN"})
        String availability,
    @Schema(
            description = "readAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime readAt,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime createdAt) {
  @Schema(name = "NotificationResponseTarget")
  public record Target(
      @Schema(
              description = "storeId",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "shop_moment")
          String storeId,
      @Schema(
              description = "slotId",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "slot_17_1600")
          String slotId,
      @Schema(
              description = "슬롯 발행 당시 버전. 일반 슬롯은 null",
              requiredMode = Schema.RequiredMode.REQUIRED,
              types = {"integer", "null"},
              format = "int64",
              example = "2")
          Long publicationVersion,
      @Schema(
              description = "일치 대기 ID 목록. 앱 전체 맞춤 알림은 빈 배열",
              requiredMode = Schema.RequiredMode.REQUIRED)
          List<String> waitlistIds) {}

  @Schema(name = "NotificationResponseAction")
  public record Action(
      @Schema(
              description = "type",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "OPEN_SLOT",
              allowableValues = {"OPEN_SLOT"})
          String type,
      @Schema(
              description = "path",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "/customer/shops/shop_moment?slotId=slot_17_1600")
          String path) {}
}
