package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "StoreDetailResponse")
public record StoreDetailResponse(
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
            description = "description",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "취향을 함께 고민하는 네일 공간")
        String description,
    @Schema(
            description = "address",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "서울 성동구 연무장길 18, 2층")
        String address,
    @Schema(description = "location", requiredMode = Schema.RequiredMode.REQUIRED)
        Location location,
    @Schema(
            description = "imageUrl",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "https://example.com/moment.jpg")
        String imageUrl,
    @Schema(description = "rating", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        Rating rating,
    @Schema(
            description = "timezone",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Asia/Seoul")
        String timezone,
    @Schema(
            description = "MON~SUN 각 1개, 총 7개. 휴무일은 opensAt/closesAt=null",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<OpeningHourItem> openingHours,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version) {
  @Schema(name = "StoreDetailResponseLocation")
  public record Location(
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

  @Schema(name = "StoreDetailResponseRating")
  public record Rating(
      @Schema(description = "average", requiredMode = Schema.RequiredMode.REQUIRED, example = "4.9")
          Double average,
      @Schema(description = "count", requiredMode = Schema.RequiredMode.REQUIRED, example = "12")
          Integer count) {}

  @Schema(name = "StoreDetailResponseOpeningHourItem")
  public record OpeningHourItem(
      @Schema(
              description = "dayOfWeek",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "MON",
              allowableValues = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"})
          String dayOfWeek,
      @Schema(
              description = "opensAt",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              example = "09:00",
              type = "string",
              format = "time",
              pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
          @com.fasterxml.jackson.annotation.JsonFormat(pattern = "HH:mm")
          LocalTime opensAt,
      @Schema(
              description = "closesAt",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              example = "20:00",
              type = "string",
              format = "time",
              pattern = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
          @com.fasterxml.jackson.annotation.JsonFormat(pattern = "HH:mm")
          LocalTime closesAt,
      @Schema(
              description = "closed",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "false")
          Boolean closed) {}
}
