package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UserPreferencesResponse")
public record UserPreferencesResponse(
    @Schema(description = "categories", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> categories,
    @Schema(description = "keywords", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> keywords,
    @Schema(
            description = "최대 금액, 0~10000000원. null은 상한 없음",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "60000")
        Long maxPrice,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
        Long version) {}
