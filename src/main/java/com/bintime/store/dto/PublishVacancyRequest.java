package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "PublishVacancyRequest")
public record PublishVacancyRequest(
    @Schema(
            description = "serviceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "svc_nail_01")
        String serviceId,
    @Schema(
            description = "startAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T17:00:00+09:00")
        OffsetDateTime startAt,
    @Schema(description = "discount", requiredMode = Schema.RequiredMode.REQUIRED)
        Discount discount,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion) {
  @Schema(name = "PublishVacancyRequestDiscount")
  public record Discount(
      @Schema(
              description = "type",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "PERCENT",
              allowableValues = {"PERCENT", "FIXED_PRICE"})
          String type,
      @Schema(description = "value", requiredMode = Schema.RequiredMode.REQUIRED, example = "30")
          Long value) {}
}
