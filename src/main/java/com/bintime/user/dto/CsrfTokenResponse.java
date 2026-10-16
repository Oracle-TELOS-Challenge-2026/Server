package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "CsrfTokenResponse")
public record CsrfTokenResponse(
    @Schema(
            description = "csrfToken",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "csrf-example")
        String csrfToken,
    @Schema(
            description = "expiresAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "2026-10-23T14:20:00+09:00")
        OffsetDateTime expiresAt) {}
