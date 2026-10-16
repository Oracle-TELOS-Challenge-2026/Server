package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CancelReservationRequest")
public record CancelReservationRequest(
    @Schema(
            description = "reasonCode",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "CUSTOMER_REQUEST",
            allowableValues = {"CUSTOMER_REQUEST", "STORE_REQUEST", "OTHER"})
        String reasonCode,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion,
    @Schema(
            description = "OTHER이면 1~300자 필수, 나머지는 생략 가능",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        String reason) {}
