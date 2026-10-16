package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "ChatRoomResponse")
public record ChatRoomResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "room_001")
        String id,
    @Schema(description = "store", requiredMode = Schema.RequiredMode.REQUIRED) Store store,
    @Schema(description = "customer", requiredMode = Schema.RequiredMode.REQUIRED)
        Customer customer,
    @Schema(
            description = "status",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "OPEN",
            allowableValues = {"OPEN", "READ_ONLY"})
        String status,
    @Schema(
            description = "lastMessage",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true)
        ChatMessageResponse lastMessage,
    @Schema(description = "unreadCount", requiredMode = Schema.RequiredMode.REQUIRED, example = "0")
        Integer unreadCount,
    @Schema(
            description = "본인의 마지막 읽음 위치. 0 이상, 최신 sequence 이하",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "0")
        Long lastReadSequence,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:00:00+09:00")
        OffsetDateTime createdAt) {
  @Schema(name = "ChatRoomResponseStore")
  public record Store(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "shop_moment")
          String id,
      @Schema(description = "name", requiredMode = Schema.RequiredMode.REQUIRED, example = "네일 모먼트")
          String name,
      @Schema(
              description = "imageUrl",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true)
          String imageUrl) {}

  @Schema(name = "ChatRoomResponseCustomer")
  public record Customer(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "usr_001")
          String id,
      @Schema(
              description = "displayName",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "빈틈고객")
          String displayName) {}
}
