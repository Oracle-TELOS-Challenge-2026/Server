package com.bintime.notification.contract;

/** 활성 기기 연결과 같은 트랜잭션에서 발행한다. 기존 유효한 빈자리 탐색에도 사용한다. */
public record PushSubscriptionRegistered(String userId) {}
