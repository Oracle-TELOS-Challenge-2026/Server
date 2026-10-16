package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;

@Schema(description = "매장 시술 등록. 카테고리는 매장 카테고리를 따른다")
public record CreateStoreServiceRequest(
    @NotBlank @Size(max = 100) String name,
    @NotNull @Min(15) @Max(480) @Schema(description = "5분 단위") Integer durationMinutes,
    @NotNull @Min(0) @Max(10000000) Long basePrice,
    @NotNull Boolean active) {}
