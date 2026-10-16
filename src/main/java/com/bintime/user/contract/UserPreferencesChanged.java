package com.bintime.user.contract;

/** 단일 앱에서 선호 저장 트랜잭션에 참여할 소비자에게 전달한다. */
public record UserPreferencesChanged(String userId) {}
