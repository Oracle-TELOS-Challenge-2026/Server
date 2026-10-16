package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "WithdrawVacancyRequest")
public record WithdrawVacancyRequest(
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2")
        Long expectedVersion) {}
