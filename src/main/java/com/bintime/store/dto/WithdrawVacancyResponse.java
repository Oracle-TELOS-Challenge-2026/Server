package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "WithdrawVacancyResponse")
public record WithdrawVacancyResponse(
    @Schema(description = "vacancy", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        VacancyResponse vacancy,
    @Schema(description = "slot", requiredMode = Schema.RequiredMode.REQUIRED)
        ReservationSlotResponse slot) {}
