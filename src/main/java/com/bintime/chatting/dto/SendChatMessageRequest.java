package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "SendChatMessageRequest")
public record SendChatMessageRequest(
    @Schema(
            description = "REST·WebSocket 재전송 시 유지하는 메시지 중복 방지 ID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
        String clientMessageId,
    @Schema(
            description = "content",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "내일 오후 4시 예약 가능한가요?")
        String content) {}
