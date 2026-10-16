package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "UpdateProfileRequest")
public record UpdateProfileRequest(
    @Schema(
            description = "displayName",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "빈틈고객")
        @NotBlank
        String displayName,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        @NotNull
        @Positive
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long expectedVersion) {}
