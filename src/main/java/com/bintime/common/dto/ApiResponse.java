package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JSON 성공 응답. OAuth 리다이렉트, 204, SSE, WebSocket은 제외")
public record ApiResponse<T>(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) T data,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ResponseMeta meta) {}
