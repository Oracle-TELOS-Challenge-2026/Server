package com.bintime.user.security;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("bintime.auth")
public record KakaoLoginProperties(
    String clientId,
    String clientSecret,
    String redirectUri,
    String frontendUrl,
    List<String> allowedReturnPaths,
    boolean corsEnabled) {

  public boolean configured() {
    return clientId != null && !clientId.isBlank();
  }

  public String returnPath(String requested) {
    String path = requested == null ? "/" : requested;
    // 정확히 등록한 상대 경로만 허용해 외부 사이트로의 로그인 리다이렉트를 막는다.
    if (!path.matches("/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]*")
        || !allowedReturnPaths.contains(path)) {
      throw new IllegalArgumentException("returnTo is not an allowed app path");
    }
    return path;
  }

  public String frontendOrigin() {
    URI uri = URI.create(frontendUrl);
    if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
      throw new IllegalArgumentException("FRONTEND_URL must be an HTTP(S) origin");
    }
    return uri.getScheme() + "://" + uri.getRawAuthority();
  }
}
