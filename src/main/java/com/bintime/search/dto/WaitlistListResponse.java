package com.bintime.search.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "WaitlistListResponse")
public record WaitlistListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<WaitlistResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page,
    @Schema(description = "counts", requiredMode = Schema.RequiredMode.REQUIRED) Counts counts) {
  @Schema(name = "WaitlistListResponseCounts")
  public record Counts(
      @Schema(description = "active", requiredMode = Schema.RequiredMode.REQUIRED, example = "2")
          Integer active,
      @Schema(description = "fulfilled", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
          Integer fulfilled,
      @Schema(description = "canceled", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
          Integer canceled,
      @Schema(description = "expired", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
          Integer expired) {}
}
