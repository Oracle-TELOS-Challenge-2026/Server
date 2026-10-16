package com.bintime.search.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "확인된 대기 조건. 매장 미지정이면 location/radiusMeters 필수. maxPrice는 필수 nullable")
public record WaitlistConditions(
    @Schema(description = "storeId", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        String storeId,
    @Schema(
            description = "category",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "NAIL",
            allowableValues = {"NAIL", "HAIR", "EYELASH", "SKIN"})
        @JsonProperty(required = true)
        String category,
    @Schema(description = "serviceId", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        String serviceId,
    @Schema(
            description = "방문 가능 구간 시작, 오프셋 포함",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-17T15:00:00+09:00")
        @JsonProperty(required = true)
        OffsetDateTime windowStart,
    @Schema(
            description = "시술 종료 기한. 시술 전체가 희망 구간 안에 포함되어야 함",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-17T18:00:00+09:00")
        @JsonProperty(required = true)
        OffsetDateTime windowEnd,
    @Schema(description = "location", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        @JsonProperty(required = true)
        Location location,
    @Schema(
            description = "직선거리 반경, 100~10000m",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "3000")
        @JsonProperty(required = true)
        Integer radiusMeters,
    @Schema(
            description = "최대 금액, 0~10000000원. null은 상한 없음",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "60000")
        @JsonProperty(required = true)
        Long maxPrice,
    @Schema(description = "keywords", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty(required = true)
        List<String> keywords) {
  @Schema(name = "WaitlistConditionsLocation")
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
}
