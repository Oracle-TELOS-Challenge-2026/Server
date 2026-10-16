package com.bintime.store.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "VacancyListResponse")
public record VacancyListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<VacancyResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page,
    @Schema(description = "counts", requiredMode = Schema.RequiredMode.REQUIRED) Counts counts) {
  @Schema(name = "VacancyListResponseCounts")
  public record Counts(
      @Schema(
              description = "awaitingPublication",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "2")
          Integer awaitingPublication,
      @Schema(description = "published", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
          Integer published,
      @Schema(description = "filled", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
          Integer filled) {}
}
