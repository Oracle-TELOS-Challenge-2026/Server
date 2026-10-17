package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SaveNotificationPreferencesRequest(
    @NotNull
        @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            deprecated = true,
            description = "기존 설정 저장값. false로 저장해도 발송을 차단하지 않는다")
        Boolean personalizedVacancyEnabled,
    @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion) {}
