package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "ChatMessageResponse")
public record ChatMessageResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "msg_001")
        String id,
    @Schema(
            description = "roomId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "room_001")
        String roomId,
    @Schema(
            description = "방별 커밋 순서. JavaScript 안전 정수 범위",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long sequence,
    @Schema(description = "sender", requiredMode = Schema.RequiredMode.REQUIRED) Sender sender,
    @Schema(
            description = "REST·WebSocket 재전송 시 유지하는 메시지 중복 방지 ID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
        String clientMessageId,
    @Schema(
            description = "type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TEXT",
            allowableValues = {"TEXT"})
        String type,
    @Schema(
            description = "content",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "내일 오후 4시 예약 가능한가요?")
        String content,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:10:00+09:00")
        OffsetDateTime createdAt) {
  @Schema(name = "ChatMessageResponseSender")
  public record Sender(
      @Schema(
              description = "불투명 리소스 ID, 최대 64자",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "usr_001")
          String id,
      @Schema(
              description = "role",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "CUSTOMER",
              allowableValues = {"CUSTOMER", "OWNER"})
          String role,
      @Schema(
              description = "displayName",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "빈틈고객")
          String displayName) {}
}
