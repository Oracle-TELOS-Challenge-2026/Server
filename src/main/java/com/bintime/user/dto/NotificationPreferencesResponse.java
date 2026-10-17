package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "기존 맞춤 알림 설정 저장값. 현재 발송 자격과는 별개")
public record NotificationPreferencesResponse(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            deprecated = true,
            description = "기존 저장값. 기본 false이며 false/N도 현재 알림 후보에 포함된다")
        boolean personalizedVacancyEnabled,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "프로필·선호도와 공유하는 현재 사용자 버전")
        Long version) {}
