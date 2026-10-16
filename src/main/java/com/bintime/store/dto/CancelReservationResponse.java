package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CancelReservationResponse")
public record CancelReservationResponse(
    @Schema(description = "reservation", requiredMode = Schema.RequiredMode.REQUIRED)
        ReservationResponse reservation,
    @Schema(description = "vacancy", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        VacancyResponse vacancy) {}
