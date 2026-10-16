package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "ChatReadResponse")
public record ChatReadResponse(
    @Schema(
            description = "roomId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "room_001")
        String roomId,
    @Schema(
            description = "본인의 마지막 읽음 위치. 0 이상, 최신 sequence 이하",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long lastReadSequence,
    @Schema(
            description = "readAt",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "2026-10-16T14:20:00+09:00")
        OffsetDateTime readAt) {}
