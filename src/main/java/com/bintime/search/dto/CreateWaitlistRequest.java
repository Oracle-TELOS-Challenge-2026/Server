package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CreateWaitlistRequest")
public record CreateWaitlistRequest(
    @Schema(
            description = "source",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "NATURAL_LANGUAGE",
            allowableValues = {"STORE_DETAIL", "RESERVATION_FAILED", "NATURAL_LANGUAGE", "MANUAL"})
        String source,
    @Schema(description = "예약 조건. 변경 시 객체 전체 교체", requiredMode = Schema.RequiredMode.REQUIRED)
        WaitlistConditions conditions) {}
