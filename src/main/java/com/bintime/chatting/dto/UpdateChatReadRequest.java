package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "UpdateChatReadRequest")
public record UpdateChatReadRequest(
    @Schema(
            description = "본인의 마지막 읽음 위치. 0 이상, 최신 sequence 이하",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long lastReadSequence) {}
