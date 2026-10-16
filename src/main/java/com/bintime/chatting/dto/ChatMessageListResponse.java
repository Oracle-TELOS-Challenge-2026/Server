package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "ChatMessageListResponse")
public record ChatMessageListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<ChatMessageResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) Page page) {
  @Schema(name = "ChatMessageListResponsePage")
  public record Page(
      @Schema(
              description = "hasOlder",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "false")
          Boolean hasOlder,
      @Schema(
              description = "nextBeforeSequence",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          Long nextBeforeSequence) {}
}
