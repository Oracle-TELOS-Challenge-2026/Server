package com.bintime.user.adapter;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.contract.SearchUserAccess;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.UserAccountAccess;
import com.bintime.user.service.UserPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.stereotype.Component;

/** 검색 도메인의 공개 계약을 기존 로그인 세션과 브라우저 알림 동의에 연결한다. */
@Component
@Primary
@Lazy
@Profile("!api-docs")
public class SessionSearchUserAccess implements SearchUserAccess {
  private final UserAccountAccess accounts;
  private final UserPreferenceService preferences;
  private final ObjectProvider<NotificationSubscriptions> subscriptions;
  private final HttpSessionCsrfTokenRepository csrf;

  public SessionSearchUserAccess(
      UserAccountAccess accounts,
      UserPreferenceService preferences,
      ObjectProvider<NotificationSubscriptions> subscriptions,
      HttpSessionCsrfTokenRepository csrf) {
    this.accounts = accounts;
    this.preferences = preferences;
    this.subscriptions = subscriptions;
    this.csrf = csrf;
  }

  @Override
  public String customer(HttpServletRequest request, String csrfToken, boolean mutation) {
    if (!(request.getUserPrincipal() instanceof Authentication authentication)
        || !authentication.isAuthenticated()
        || !(authentication.getPrincipal() instanceof BintimePrincipal principal)
        || request.getSession(false) == null) {
      throw new ApiBusinessException(401, "UNAUTHORIZED");
    }
    if (!"CUSTOMER".equals(accounts.get(principal.userId()).role())) {
      throw new ApiBusinessException(403, "FORBIDDEN");
    }
    // SearchHttpGuard가 호출하는 변경 요청도 로그인과 같은 세션 토큰을 검증한다.
    if (mutation) {
      var expected = csrf.loadToken(request);
      if (expected == null
          || csrfToken == null
          || !MessageDigest.isEqual(
              expected.getToken().getBytes(StandardCharsets.UTF_8),
              csrfToken.getBytes(StandardCharsets.UTF_8))) {
        throw new ApiBusinessException(403, "CSRF_INVALID");
      }
    }
    return principal.userId();
  }

  @Override
  public boolean notificationsEnabled(String userId) {
    return subscriptions.getObject().hasActive(userId);
  }

  @Override
  public String explicitPreferences(String userId) {
    return preferences.explicitPreferences(userId);
  }
}
