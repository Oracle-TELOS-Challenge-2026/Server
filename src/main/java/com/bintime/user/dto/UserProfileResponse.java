package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "UserProfileResponse")
public record UserProfileResponse(
    @Schema(
            description = "불투명 리소스 ID, 최대 64자",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "usr_001")
        String id,
    @Schema(
            description = "displayName",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "빈틈고객")
        String displayName,
    @Schema(description = "roles", requiredMode = Schema.RequiredMode.REQUIRED) List<String> roles,
    @Schema(description = "ownedStoreIds", requiredMode = Schema.RequiredMode.REQUIRED)
        List<String> ownedStoreIds,
    @Schema(description = "location", requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
        UserLocationResponse location,
    @Schema(description = "preferences", requiredMode = Schema.RequiredMode.REQUIRED)
        Preferences preferences,
    @Schema(description = "리소스 버전", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
        Long version) {
  @Schema(name = "UserProfileResponsePreferences")
  public record Preferences(
      @Schema(description = "categories", requiredMode = Schema.RequiredMode.REQUIRED)
          List<String> categories,
      @Schema(description = "keywords", requiredMode = Schema.RequiredMode.REQUIRED)
          List<String> keywords,
      @Schema(
              description = "최대 금액, 0~10000000원. null은 상한 없음",
              requiredMode = Schema.RequiredMode.REQUIRED,
              nullable = true,
              example = "60000")
          Long maxPrice) {}
}
