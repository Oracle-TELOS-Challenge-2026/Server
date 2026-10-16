package com.bintime.user.security;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

/** 다른 도메인에서 @AuthenticationPrincipal로 현재 사용자의 userId를 조회한다. */
public record BintimePrincipal(String userId, String role) implements OAuth2User, Serializable {
  @Override
  public String getName() {
    return userId;
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @Override
  public Map<String, Object> getAttributes() {
    return Map.of("userId", userId);
  }
}
