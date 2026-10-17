package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Schema(description = "손님용 조회 전용 시간표. 예약자·예약·슬롯 식별자를 포함하지 않는다")
public record CustomerTimetableResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-20") LocalDate date,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Asia/Seoul") String timezone,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OpeningHours openingHours,
    @Schema(description = "확정 예약 구간만 반환. 없으면 빈 배열", requiredMode = Schema.RequiredMode.REQUIRED)
        List<TimeRange> busyTimes,
    @Schema(
            description = "공개된 유효 미래 슬롯. 두 목록 밖의 영업시간은 확인 필요",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<TimeRange> availableTimes) {
  @Schema(name = "CustomerTimetableOpeningHours")
  public record OpeningHours(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean closed,
      @Schema(
              description = "HH:mm. 휴무이면 null",
              nullable = true,
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "09:00")
          String opensAt,
      @Schema(
              description = "HH:mm. 휴무이면 null",
              nullable = true,
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "18:00")
          String closesAt) {}

  @Schema(name = "CustomerTimetableTimeRange", description = "시작 포함·종료 제외의 한국 시간 구간")
  public record TimeRange(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-20T10:00:00+09:00")
          OffsetDateTime startAt,
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-10-20T11:00:00+09:00")
          OffsetDateTime endAt) {}
}
