package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "CreateChatRoomRequest")
public record CreateChatRoomRequest(
    @Schema(
            description = "storeId",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "shop_moment")
        String storeId) {}
