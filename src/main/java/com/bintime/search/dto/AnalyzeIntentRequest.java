package com.bintime.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "AnalyzeIntentRequest")
public record AnalyzeIntentRequest(
    @Schema(
            description = "text",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "내일 오후 3시부터 6시 사이에 성수역 근처에서 6만원 이하로 네일 받고 싶어")
        String text,
    @Schema(description = "context", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Context context) {
  @Schema(name = "AnalyzeIntentRequestContext")
  public record Context(
      @Schema(
              description = "storeId",
              requiredMode = Schema.RequiredMode.NOT_REQUIRED,
              nullable = true)
          String storeId,
      @Schema(description = "location", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
          ContextLocation location) {}

  @Schema(name = "AnalyzeIntentRequestContextLocation")
  public record ContextLocation(
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
