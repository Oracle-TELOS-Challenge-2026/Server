package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record CursorPage(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            description = "다음 페이지가 없으면 null")
        String nextCursor,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Boolean hasNext) {}
