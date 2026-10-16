package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "MatchJobResponse")
public record MatchJobResponse(
    @Schema(description = "jobId", requiredMode = Schema.RequiredMode.REQUIRED, example = "job_001")
        String jobId,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "QUEUED",
            allowableValues = {"QUEUED"})
        String status) {}
