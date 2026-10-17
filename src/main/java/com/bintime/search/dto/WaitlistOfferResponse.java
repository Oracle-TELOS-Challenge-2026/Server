package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "WaitlistOfferResponse")
public record WaitlistOfferResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "offer_001")
        String id,
    @Schema(
            description = "slotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_17_1600")
        String slotId,
    @Schema(
            description = "슬롯 발행 당시 버전. 일반 슬롯은 null",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2")
        Long publicationVersion,
    @Schema(
            description = "storeId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop_moment")
        String storeId,
    @Schema(
            description = "storeName",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "네일 모먼트")
        String storeName,
    @Schema(
            description = "serviceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "svc_nail_01")
        String serviceId,
    @Schema(
            description = "serviceName",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "원컬러 젤 네일")
        String serviceName,
    @Schema(
            description = "startAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-17T16:00:00+09:00")
        OffsetDateTime startAt,
    @Schema(
            description = "endAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-17T17:00:00+09:00")
        OffsetDateTime endAt,
    @Schema(
            description = "originalPrice",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "50000")
        Long originalPrice,
    @Schema(
            description = "payablePrice",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "35000")
        Long payablePrice,
    @Schema(
            description = "currency",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "KRW",
            allowableValues = {"KRW"})
        String currency,
    @Schema(description = "imageUrl", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        String imageUrl,
    @Schema(
            description = "validatedAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime validatedAt,
    @Schema(description = "고객 신청 즉시확정 여부", requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean instantBooking) {}
