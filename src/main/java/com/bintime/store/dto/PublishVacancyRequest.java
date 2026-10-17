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
        Long expectedVersion,
    @Schema(
            description = "true: 고객 신청 즉시 확정. false: 여러 신청 중 사장님 선택 후 확정. 생략·null은 기존 동작인 true",
            defaultValue = "true")
        Boolean instantBooking) {
  public PublishVacancyRequest(
      String serviceId, OffsetDateTime startAt, Discount discount, Long expectedVersion) {
    this(serviceId, startAt, discount, expectedVersion, true);
  }

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
