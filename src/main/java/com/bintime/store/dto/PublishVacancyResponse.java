package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "PublishVacancyResponse")
public record PublishVacancyResponse(
    @Schema(description = "vacancy", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        VacancyResponse vacancy,
    @Schema(description = "slot", requiredMode = Schema.RequiredMode.REQUIRED)
        ReservationSlotResponse slot,
    @Schema(
            description = "matchingStatus",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "QUEUED",
            allowableValues = {"QUEUED"})
        String matchingStatus) {}
