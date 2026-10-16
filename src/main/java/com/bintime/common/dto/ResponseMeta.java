package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record ResponseMeta(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "req_001") String requestId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "서버 기준 시각, +09:00")
        OffsetDateTime serverTime) {}
