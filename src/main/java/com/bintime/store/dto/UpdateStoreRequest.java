package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalTime;
import java.util.List;

@Schema(description = "매장 운영 정보 변경. description/openingHours 중 1개 이상과 expectedVersion 필수. 누락은 유지")
public record UpdateStoreRequest(
    @Schema(
            description = "description",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            example = "취향을 함께 고민하는 따뜻한 네일 공간")
        String description,
    @Schema(
            description = "변경 전 리소스 버전. 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long expectedVersion,
    @Schema(
            description = "MON~SUN 각 1개, 총 7개. 휴무일은 opensAt/closesAt=null",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<OpeningHourItem> openingHours) {
  @Schema(name = "UpdateStoreRequestOpeningHourItem")
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
