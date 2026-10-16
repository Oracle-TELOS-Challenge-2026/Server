package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "PushSubscriptionResponse")
public record PushSubscriptionResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "push_001")
        String id,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime createdAt,
    @Schema(description = "expiresAt", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        OffsetDateTime expiresAt) {}
