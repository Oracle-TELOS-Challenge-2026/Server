package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "VacancyResponse")
public record VacancyResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "vac_001")
        String id,
    @Schema(
            description = "storeId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop_moment")
        String storeId,
    @Schema(
            description = "sourceReservationId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "rsv_old")
        String sourceReservationId,
    @Schema(
            description = "OTHER이면 1~300자 필수, 나머지는 생략 가능",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "CANCELLATION",
            allowableValues = {"CANCELLATION", "NO_SHOW"})
        String reason,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DRAFT",
            allowableValues = {"DRAFT", "PUBLISHED", "FILLED", "WITHDRAWN", "EXPIRED"})
        String status,
    @Schema(
            description = "resourceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "staff_01")
        String resourceId,
    @Schema(
            description = "availableFrom",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T17:00:00+09:00")
        OffsetDateTime availableFrom,
    @Schema(
            description = "availableUntil",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T18:00:00+09:00")
        OffsetDateTime availableUntil,
    @Schema(
            description = "originalServiceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "svc_nail_01")
        String originalServiceId,
    @Schema(
            description = "originalPrice",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "50000")
        Long originalPrice,
    @Schema(
            description = "publishedSlotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        String publishedSlotId,
    @Schema(
            description = "publishedAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        OffsetDateTime publishedAt,
    @Schema(
            description = "occurredAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:10:00+09:00")
        OffsetDateTime occurredAt,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version,
    @Schema(description = "발행할 빈자리의 즉시확정 여부", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean instantBooking) {}
