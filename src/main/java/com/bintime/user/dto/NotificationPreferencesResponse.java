package com.bintime.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "앱 전체 맞춤 빈자리 알림 설정. 개별 매장·대기 등록과 별개")
public record NotificationPreferencesResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "맞춤 빈자리 알림 동의. 기본 false")
        boolean personalizedVacancyEnabled,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "프로필·선호도와 공유하는 현재 사용자 버전")
        Long version) {}
