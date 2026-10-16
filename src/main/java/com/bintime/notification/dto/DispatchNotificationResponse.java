package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "DispatchNotificationResponse")
public record DispatchNotificationResponse(
    @Schema(
            description = "알림 ID. SKIPPED 상태에서는 null",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "noti_001")
        String notificationId,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "QUEUED",
            allowableValues = {"QUEUED", "SKIPPED"})
        String status) {}
