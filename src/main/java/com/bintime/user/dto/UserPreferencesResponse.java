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
    @Schema(
            description = "자유 입력 선호도. 미등록 시 빈 문자열",
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 2000)
        String text,
    @Schema(
            description = "저장 후 이름·선호도 공통 버전. 다음 수정 요청의 expectedVersion으로 사용",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2")
        Long version) {}
