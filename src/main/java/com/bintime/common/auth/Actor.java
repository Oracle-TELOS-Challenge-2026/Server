package com.bintime.common.auth;

import java.security.Principal;

/** 실제 인증 어댑터가 검증된 세션에서 제공하는 요청자. HTTP 헤더로 운영 권한을 만들지 않는다. */
public record Actor(String id, String role, String displayName) implements Principal {
  public String getName() {
    return id;
  }
}
