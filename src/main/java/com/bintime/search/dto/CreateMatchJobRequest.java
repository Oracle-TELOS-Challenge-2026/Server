package com.bintime.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CreateMatchJobRequest")
public record CreateMatchJobRequest(
    @Schema(
            description = "eventId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "evt_001")
        @JsonProperty(required = true)
        String eventId,
    @Schema(
            description = "slotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_17_1600")
        @JsonProperty(required = true)
        String slotId,
    @Schema(
            description = "슬롯 발행 당시 버전. 일반 슬롯은 null",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2")
        @JsonProperty(required = true)
        Long publicationVersion) {}
