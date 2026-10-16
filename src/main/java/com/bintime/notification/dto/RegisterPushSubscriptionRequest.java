package com.bintime.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "RegisterPushSubscriptionRequest")
public record RegisterPushSubscriptionRequest(
    @Schema(
            description = "endpoint",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "https://push.example.net/subscription/opaque")
        String endpoint,
    @Schema(description = "keys", requiredMode = Schema.RequiredMode.REQUIRED) Keys keys,
    @Schema(
            description = "미래 Unix epoch 밀리초 또는 null",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            nullable = true)
        Long expirationTime) {
  @Schema(name = "RegisterPushSubscriptionRequestKeys")
  public record Keys(
      @Schema(
              description = "p256dh",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "base64url-key")
          String p256dh,
      @Schema(
              description = "auth",
              requiredMode = Schema.RequiredMode.REQUIRED,
              example = "base64url-secret")
          String auth) {}
}
