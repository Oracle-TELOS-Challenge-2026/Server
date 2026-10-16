package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "시술 변경. name/basePrice/active 중 1개 이상과 expectedVersion 필수. null 삭제는 허용하지 않음")
public record UpdateStoreServiceRequest(
    @Schema(
            description = "name",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "원컬러 젤 네일")
        String name,
    @Schema(
            description = "basePrice",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "55000")
        Long basePrice,
    @Schema(
            description = "active",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "true")
        Boolean active,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion) {}
