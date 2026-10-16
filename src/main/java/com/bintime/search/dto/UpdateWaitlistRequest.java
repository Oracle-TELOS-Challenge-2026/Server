package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "UpdateWaitlistRequest")
public record UpdateWaitlistRequest(
    @Schema(description = "예약 조건. 변경 시 객체 전체 교체", requiredMode = Schema.RequiredMode.REQUIRED)
        WaitlistConditions conditions,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion) {}
