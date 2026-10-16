package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "RegisterPushSubscriptionRequest")
public record RegisterPushSubscriptionRequest(
    @Schema(
            description = "Firebase onRegistered가 반환한 FID. 공백·제어문자 금지",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1,
            maxLength = 128,
            example = "browser-installation-id")
        @JsonDeserialize(using = com.bintime.user.dto.StrictStringDeserializer.class)
        String installationId) {}
