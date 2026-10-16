package com.bintime.store.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ReservationListResponse")
public record ReservationListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ReservationResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page,
    @Schema(description = "counts", requiredMode = Schema.RequiredMode.REQUIRED) Counts counts) {
  @Schema(name = "ReservationListResponseCounts")
  public record Counts(
      @Schema(description = "upcoming", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
          Integer upcoming,
      @Schema(description = "past", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
          Integer past,
      @Schema(description = "canceled", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
          Integer canceled) {}
}
