package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "AnalyzeIntentResponse")
public record AnalyzeIntentResponse(
    @Schema(
            description = "referenceTime",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:00:00+09:00")
        OffsetDateTime referenceTime,
    @Schema(
            description = "timezone",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Asia/Seoul")
        String timezone,
    @Schema(description = "예약 조건. 변경 시 객체 전체 교체", requiredMode = Schema.RequiredMode.REQUIRED)
        PartialIntentConditions conditions,
    @Schema(
            description = "resolution",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "RESOLVED",
            allowableValues = {"RESOLVED", "NEEDS_CLARIFICATION", "UNSUPPORTED"})
        String resolution,
    @Schema(description = "missingFields", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> missingFields,
    @Schema(
            description = "clarificationQuestion",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        String clarificationQuestion) {}
