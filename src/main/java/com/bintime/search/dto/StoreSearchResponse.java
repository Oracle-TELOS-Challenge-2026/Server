package com.bintime.search.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "StoreSearchResponse")
public record StoreSearchResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page,
    @Schema(
            description = "snapshotAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime snapshotAt) {
  @Schema(name = "StoreSearchResponseItem")
  public record Item(
      @Schema(description = "store", requiredMode = Schema.RequiredMode.REQUIRED) ItemStore store,
      @Schema(description = "location", requiredMode = Schema.RequiredMode.REQUIRED)
          ItemLocation location,
      @Schema(
              description = "distanceMeters",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "220")
          Integer distanceMeters,
      @Schema(
              description = "MVP에서는 null. 도보 경로 계산 미구현",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          Integer walkingMinutes,
      @Schema(
              description = "예약 가능한 대표 슬롯. 기본 지도 조회에서 슬롯이 없는 매장도 포함하며 이 값은 null",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          ItemBestSlot bestSlot,
      @Schema(description = "recommendationReasons", requiredMode = Schema.RequiredMode.REQUIRED)
          List<String> recommendationReasons) {}

  @Schema(name = "StoreSearchResponseItemStore")
  public record ItemStore(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "shop_moment")
          String id,
      @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "네일 모먼트")
          String name,
      @Schema(
              description = "category",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "NAIL",
              allowableValues = {"NAIL", "HAIR", "EYELASH", "SKIN"})
          String category,
      @Schema(
              description = "imageUrl",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          String imageUrl,
      @Schema(description = "rating", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
          ItemStoreRating rating) {}

  @Schema(name = "StoreSearchResponseItemLocation")
  public record ItemLocation(
      @Schema(
              description = "latitude",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "37.5445")
          Double latitude,
      @Schema(
              description = "longitude",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "127.0557")
          Double longitude) {}

  @Schema(name = "StoreSearchResponseItemBestSlot")
  public record ItemBestSlot(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "slot_1700")
          String id,
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
              example = "2026-10-16T17:00:00+09:00")
          OffsetDateTime startAt,
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
      @Schema(
              description = "source",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "VACANCY",
              allowableValues = {"REGULAR", "VACANCY"})
          String source,
      @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "3")
          Long version) {}

  @Schema(name = "StoreSearchResponseItemStoreRating")
  public record ItemStoreRating(
      @Schema(description = "average", requiredMode = Schema.RequiredMode.REQUIRED, example = "4.9")
          Double average,
      @Schema(description = "count", requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
          Integer count) {}
}
