package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "ReservationSlotResponse")
public record ReservationSlotResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_1700")
        String id,
    @Schema(
            description = "storeId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop_moment")
        String storeId,
    @Schema(
            description = "serviceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "svc_nail_01")
        String serviceId,
    @Schema(
            description = "사장님 응답에서만 제공하는 단일 예약 자원",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true,
            example = "staff_01")
        @com.fasterxml.jackson.annotation.JsonInclude(
            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        String resourceId,
    @Schema(
            description = "startAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T17:00:00+09:00")
        OffsetDateTime startAt,
    @Schema(
            description = "endAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T18:00:00+09:00")
        OffsetDateTime endAt,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "AVAILABLE",
            allowableValues = {"AVAILABLE", "BOOKED", "BLOCKED", "EXPIRED"})
        String status,
    @Schema(description = "bookable", requiredMode = Schema.RequiredMode.REQUIRED, example = "true")
        Boolean bookable,
    @Schema(
            description = "unavailableReason",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"BOOKED", "EXPIRED", "BLOCKED", "SERVICE_INACTIVE"})
        String unavailableReason,
    @Schema(
            description = "source",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "VACANCY",
            allowableValues = {"REGULAR", "VACANCY"})
        String source,
    @Schema(
            description = "vacancyId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "vac_001")
        String vacancyId,
    @Schema(
            description = "슬롯 발행 당시 버전. 일반 슬롯은 null",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "3")
        Long publicationVersion,
    @Schema(description = "price", requiredMode = Schema.RequiredMode.REQUIRED) Price price,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
        Long version,
    @Schema(
            description = "고객 신청 즉시 확정 여부. false이면 사장님 선택을 기다리며 시간을 선점하지 않는다",
            requiredMode = Schema.RequiredMode.REQUIRED)
        Boolean instantBooking) {
  @Schema(name = "ReservationSlotResponsePrice")
  public record Price(
      @Schema(
              description = "original",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "50000")
          Long original,
      @Schema(
              description = "payable",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "35000")
          Long payable,
      @Schema(
              description = "등록한 정률 할인율. 정액 할인가 발행은 null, 할인 없는 일반 슬롯은 0",
              nullable = true,
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "30")
          Integer discountPercent,
      @Schema(
              description = "currency",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "KRW",
              allowableValues = {"KRW"})
          String currency) {}
}
