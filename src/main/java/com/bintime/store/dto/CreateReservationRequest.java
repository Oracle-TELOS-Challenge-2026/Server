package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CreateReservationRequest")
public record CreateReservationRequest(
    @Schema(
            description = "slotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_1700")
        String slotId,
    @Schema(
            description = "사용자가 확인한 예약 금액, KRW 정수 원",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "35000")
        Long expectedPrice,
    @Schema(
            description = "예약 직전에 조회한 슬롯 버전",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "3")
        Long expectedSlotVersion,
    @Schema(
            description = "waitlistId",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "wait_001")
        String waitlistId) {}
