package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "UpdateReservationStatusResponse")
public record UpdateReservationStatusResponse(
    @Schema(description = "reservation", requiredMode = Schema.RequiredMode.REQUIRED)
        OwnerReservationResponse reservation,
    @Schema(description = "vacancy", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        VacancyResponse vacancy) {}
