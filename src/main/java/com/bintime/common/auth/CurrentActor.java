package com.bintime.common.auth;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.UserAccountAccess;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
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
    var authentication = SecurityContextHolder.getContext().getAuthentication();
    require(authentication != null && authentication.isAuthenticated(), 401, "UNAUTHENTICATED");
    String userId = null;
    if (authentication.getPrincipal() instanceof BintimePrincipal principal)
      userId = principal.userId();
    else if (authentication instanceof JwtAuthenticationToken jwt)
      userId = jwt.getToken().getSubject();
    require(userId != null && !userId.isBlank(), 401, "UNAUTHENTICATED");
    // 역할과 이름은 로그인 시점의 클레임이 아닌 현재 user 원장에서 읽는다.
    // 쿠키 변경 요청의 CSRF 검증은 store SecurityFilterChain이 담당한다.
    return users.get(userId);
  }
}
