package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "MarkNotificationReadRequest")
public record MarkNotificationReadRequest(
    @Schema(
            description = "true만 허용. false는 400",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        Boolean read) {}
