package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "SaveLocationRequest")
public record SaveLocationRequest(
    @Schema(
            description = "latitude",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "37.5445")
        Double latitude,
    @Schema(
            description = "longitude",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "127.0557")
        Double longitude,
    @Schema(
            description = "accuracyMeters",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "30")
        Integer accuracyMeters,
    @Schema(
            description = "source",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEVICE",
            allowableValues = {"DEVICE", "MANUAL"})
        String source,
    @Schema(
            description = "기기 관측 시각. 오래된 관측값은 409 STALE_LOCATION",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:00:00+09:00")
        OffsetDateTime observedAt) {}
