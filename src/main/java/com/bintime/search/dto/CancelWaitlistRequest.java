package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CancelWaitlistRequest")
public record CancelWaitlistRequest(
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion) {}
