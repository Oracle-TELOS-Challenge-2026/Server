package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "TimetableResponse")
public record TimetableResponse(
    @Schema(
            description = "date",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16")
        LocalDate date,
    @Schema(
            description = "timezone",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "Asia/Seoul")
        String timezone,
    @Schema(description = "summary", requiredMode = Schema.RequiredMode.REQUIRED) Summary summary,
    @Schema(description = "resources", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ResourceItem> resources,
    @Schema(description = "reservations", requiredMode = Schema.RequiredMode.REQUIRED)
        List<OwnerReservationResponse> reservations,
    @Schema(description = "availableSlots", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ReservationSlotResponse> availableSlots,
    @Schema(description = "vacancies", requiredMode = Schema.RequiredMode.REQUIRED)
        List<VacancyResponse> vacancies) {
  @Schema(name = "TimetableResponseSummary")
  public record Summary(
      @Schema(description = "total", requiredMode = Schema.RequiredMode.REQUIRED, example = "4")
          Integer total,
      @Schema(
              description = "remainingConfirmed",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "2")
          Integer remainingConfirmed) {}

  @Schema(name = "TimetableResponseResourceItem")
  public record ResourceItem(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "staff_01")
          String id,
      @Schema(description = "label", requiredMode = Schema.RequiredMode.REQUIRED, example = "담당자 1")
          String label) {}
}
