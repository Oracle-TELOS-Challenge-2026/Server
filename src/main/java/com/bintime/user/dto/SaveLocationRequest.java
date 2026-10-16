package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "SaveLocationRequest")
public record SaveLocationRequest(
    @Schema(
            description = "latitude",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "37.5445")
        @NotNull
        @DecimalMin("-90")
        @DecimalMax("90")
        Double latitude,
    @Schema(
            description = "longitude",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "127.0557")
        @NotNull
        @DecimalMin("-180")
        @DecimalMax("180")
        Double longitude,
    @Schema(
            description = "accuracyMeters",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "30")
        @PositiveOrZero
        @JsonDeserialize(using = StrictIntegerDeserializer.class)
        Integer accuracyMeters,
    @Schema(
            description = "source",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEVICE",
            allowableValues = {"DEVICE", "MANUAL"})
        @NotNull
        @Pattern(regexp = "DEVICE|MANUAL")
        String source,
    @Schema(
            description = "기기 관측 시각. 오래된 관측값은 409 STALE_LOCATION",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:00:00+09:00")
        @NotNull
        OffsetDateTime observedAt) {}
