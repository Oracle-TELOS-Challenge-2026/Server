package com.bintime.user.contract;

/** 켜는 즉시 기존 발행 빈자리도 탐색한다. 끈 경우 발송 직전 현재 동의를 다시 읽는다. */
public record NotificationPreferencesChanged(String userId, boolean enabled) {}
