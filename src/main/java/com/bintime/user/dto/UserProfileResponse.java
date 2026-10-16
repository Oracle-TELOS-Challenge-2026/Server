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
    @Schema(
            description = "이름·선호도가 공유하는 버전. 수정 요청의 expectedVersion으로 사용",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
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
          Long maxPrice,
      @Schema(
              description = "자유 입력 선호도. 미등록 시 빈 문자열",
              requiredMode = Schema.RequiredMode.REQUIRED,
              maxLength = 2000)
          String text) {
    public Preferences {
      // 기존 JSON과 신규 가입 기본값에는 text 필드가 없어도 읽을 수 있어야 한다.
      text = text == null ? "" : text;
    }
  }
}
