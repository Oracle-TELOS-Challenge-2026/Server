package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "CategoryListResponse")
public record CategoryListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items) {
  @Schema(name = "CategoryListResponseItem")
  public record Item(
      @Schema(description = "code", requiredMode = Schema.RequiredMode.REQUIRED, example = "NAIL")
          String code,
      @Schema(description = "label", requiredMode = Schema.RequiredMode.REQUIRED, example = "네일")
          String label) {}
}
