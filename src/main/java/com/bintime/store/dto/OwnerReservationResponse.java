package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "OwnerReservationResponse")
public record OwnerReservationResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "rsv_001")
        String id,
    @Schema(description = "store", requiredMode = Schema.RequiredMode.REQUIRED) Store store,
    @Schema(description = "service", requiredMode = Schema.RequiredMode.REQUIRED) Service service,
    @Schema(
            description = "slotId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "slot_1700")
        String slotId,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "CONFIRMED",
            allowableValues = {"CONFIRMED", "COMPLETED", "CANCELED", "NO_SHOW"})
        String status,
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
    @Schema(description = "price", requiredMode = Schema.RequiredMode.REQUIRED) Price price,
    @Schema(
            description = "waitlistId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "wait_001")
        String waitlistId,
    @Schema(
            description = "cancelReasonCode",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            allowableValues = {"CUSTOMER_REQUEST", "STORE_REQUEST", "OTHER"})
        String cancelReasonCode,
    @Schema(
            description = "canceledAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        OffsetDateTime canceledAt,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime createdAt,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version,
    @Schema(description = "allowedActions", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> allowedActions,
    @Schema(description = "customer", requiredMode = Schema.RequiredMode.REQUIRED)
        Customer customer,
    @Schema(
            description = "resourceId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "staff_01")
        String resourceId) {
  @Schema(name = "OwnerReservationResponseStore")
  public record Store(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "shop_moment")
          String id,
      @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "네일 모먼트")
          String name,
      @Schema(
              description = "imageUrl",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          String imageUrl) {}

  @Schema(name = "OwnerReservationResponseService")
  public record Service(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "svc_nail_01")
          String id,
      @Schema(
              description = "name",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "원컬러 젤 네일")
          String name,
      @Schema(
              description = "durationMinutes",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "60")
          Integer durationMinutes) {}

  @Schema(name = "OwnerReservationResponsePrice")
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
              description = "currency",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "KRW",
              allowableValues = {"KRW"})
          String currency) {}

  @Schema(name = "OwnerReservationResponseCustomer")
  public record Customer(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "usr_001")
          String id,
      @Schema(
              description = "displayName",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "빈틈고객")
          String displayName) {}
}
