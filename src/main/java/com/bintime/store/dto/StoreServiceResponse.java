package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "StoreServiceResponse")
public record StoreServiceResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "svc_nail_01")
        String id,
    @Schema(
            description = "storeId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop_moment")
        String storeId,
    @Schema(
            description = "category",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "NAIL",
            allowableValues = {"NAIL", "HAIR", "EYELASH", "SKIN"})
        String category,
    @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "원컬러 젤 네일")
        String name,
    @Schema(
            description = "durationMinutes",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "60")
        Integer durationMinutes,
    @Schema(
            description = "basePrice",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "50000")
        Long basePrice,
    @Schema(
            description = "currency",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "KRW",
            allowableValues = {"KRW"})
        String currency,
    @Schema(description = "active", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
        Boolean active,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version) {}
