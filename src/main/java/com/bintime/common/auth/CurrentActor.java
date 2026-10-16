package com.bintime.common.auth;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.user.service.UserAccountAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.*;

@Component
public class CurrentActor {
  private final Environment env;
  private final UserAccountAccess users;

  public CurrentActor(Environment env, @Lazy UserAccountAccess users) {
    this.env = env;
    this.users = users;
  }

  public Actor get() {
    HttpServletRequest r =
        ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
    boolean local =
        env.acceptsProfiles(Profiles.of("local", "test"))
            && !env.acceptsProfiles(Profiles.of("prod"))
            && env.getProperty("bintime.auth.local-enabled", Boolean.class, false);
    if (local && r.getHeader("X-Local-User-Id") != null)
      return users.get(r.getHeader("X-Local-User-Id"));
    require(r.getUserPrincipal() instanceof Actor, 401, "UNAUTHENTICATED");
    // 실제 인증과 CSRF 어댑터 연결 전에는 운영 보호 API를 열지 않는다.
    require(
        env.getProperty("bintime.auth.production-enabled", Boolean.class, false),
        403,
        "AUTH_INTEGRATION_REQUIRED");
    if (!java.util.Set.of("GET", "HEAD", "OPTIONS").contains(r.getMethod())) {
      var session = r.getSession(false);
      Object token = session == null ? null : session.getAttribute("bintime.csrf-token");
      String supplied = r.getHeader("X-CSRF-Token");
      require(
          token instanceof String
              && supplied != null
              && java.security.MessageDigest.isEqual(
                  ((String) token).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                  supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          403,
          "CSRF_INVALID");
    }
    return users.get(((Actor) r.getUserPrincipal()).id());
  }
}
