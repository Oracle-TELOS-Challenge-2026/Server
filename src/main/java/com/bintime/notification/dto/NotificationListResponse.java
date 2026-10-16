package com.bintime.notification.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "NotificationListResponse")
public record NotificationListResponse(
    @Schema(description = "items", requiredMode = Schema.RequiredMode.REQUIRED)
        List<NotificationResponse> items,
    @Schema(description = "page", requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page,
    @Schema(description = "unreadCount", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Integer unreadCount) {}
