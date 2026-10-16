package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "DispatchNotificationRequest")
public record DispatchNotificationRequest(
    @Schema(
            description = "eventId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "evt_match_001")
        String eventId,
    @Schema(
            description = "userId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "usr_001")
        String userId,
    @Schema(
            description = "type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VACANCY_MATCHED",
            allowableValues = {"VACANCY_MATCHED"})
        String type,
    @Schema(
            description = "slotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_17_1600")
        String slotId,
    @Schema(
            description = "슬롯 발행 당시 버전. 일반 슬롯은 null",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2")
        Long publicationVersion,
    @Schema(description = "일치한 대기 ID와 버전 목록", requiredMode = Schema.RequiredMode.REQUIRED)
        List<MatcheItem> matches,
    @Schema(
            description = "expiresAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "2026-10-17T16:00:00+09:00")
        OffsetDateTime expiresAt) {
  @Schema(name = "DispatchNotificationRequestMatcheItem")
  public record MatcheItem(
      @Schema(
              description = "waitlistId",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              example = "wait_001")
          String waitlistId,
      @Schema(
              description = "waitlistVersion",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "1")
          Long waitlistVersion) {}
}
