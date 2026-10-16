package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "WaitlistResponse")
public record WaitlistResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "wait_001")
        String id,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACTIVE",
            allowableValues = {"ACTIVE", "FULFILLED", "CANCELED", "EXPIRED"})
        String status,
    @Schema(
            description = "source",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "NATURAL_LANGUAGE",
            allowableValues = {"STORE_DETAIL", "RESERVATION_FAILED", "NATURAL_LANGUAGE", "MANUAL"})
        String source,
    @Schema(description = "예약 조건. 변경 시 객체 전체 교체", requiredMode = Schema.RequiredMode.REQUIRED)
        WaitlistConditions conditions,
    @Schema(description = "store", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        StoreSummary store,
    @Schema(description = "service", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        ServiceSummary service,
    @Schema(
            description = "availability",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AVAILABLE",
            allowableValues = {"WAITING", "AVAILABLE", "CLOSED"})
        String availability,
    @Schema(
            description = "현재 예약 가능한 후보 최대 3개. 슬롯 선점을 의미하지 않음",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<WaitlistOfferResponse> offers,
    @Schema(
            description = "reservationId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        String reservationId,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:00:00+09:00")
        OffsetDateTime createdAt,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version) {
  @Schema(name = "WaitlistResponseStoreSummary")
  public record StoreSummary(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "store_001")
          String id,
      @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "이름")
          String name,
      @Schema(
              description = "imageUrl",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          String imageUrl) {}

  @Schema(name = "WaitlistResponseServiceSummary")
  public record ServiceSummary(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "service_001")
          String id,
      @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "이름")
          String name,
      @Schema(
              description = "imageUrl",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          String imageUrl) {}
}
