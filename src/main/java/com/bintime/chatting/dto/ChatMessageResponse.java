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
            description = "TEXT 재전송 시 유지하는 중복 방지 ID. 예약 제안 카드에서는 서버가 생성한다",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
        String clientMessageId,
    @Schema(
            description = "type",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "TEXT",
            allowableValues = {"TEXT", "BOOKING_PROPOSAL"})
        String type,
    @Schema(
            description = "일반 메시지 내용 또는 예약 제안 카드의 텍스트 설명",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "내일 오후 4시 예약 가능한가요?")
        String content,
    @Schema(
            description = "createdAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-16T14:10:00+09:00")
        OffsetDateTime createdAt,
    @Schema(
            description = "BOOKING_PROPOSAL이면 최신 제안 정보. TEXT이면 null",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        ChatBookingProposalResponse bookingProposal) {
  public ChatMessageResponse(
      String id,
      String roomId,
      Long sequence,
      Sender sender,
      String clientMessageId,
      String type,
      String content,
      OffsetDateTime createdAt) {
    this(id, roomId, sequence, sender, clientMessageId, type, content, createdAt, null);
  }

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
