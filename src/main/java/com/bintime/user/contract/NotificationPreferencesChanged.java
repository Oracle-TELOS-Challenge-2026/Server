package com.bintime.user.contract;

/** 기존 설정 변경 이벤트. true 저장 시 재탐색을 유지하지만 저장값은 현재 발송 차단 조건이 아니다. */
public record NotificationPreferencesChanged(String userId, boolean enabled) {}
