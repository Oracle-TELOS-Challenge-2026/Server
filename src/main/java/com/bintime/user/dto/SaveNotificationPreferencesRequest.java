package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SaveNotificationPreferencesRequest(
    @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean personalizedVacancyEnabled,
    @NotNull @Positive @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Long expectedVersion) {}
