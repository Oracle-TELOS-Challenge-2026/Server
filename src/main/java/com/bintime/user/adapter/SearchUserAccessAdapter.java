package com.bintime.user.adapter;

import com.bintime.common.auth.CurrentActor;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.search.service.SearchFailure;
import com.bintime.user.contract.SearchUserAccess;
import com.bintime.user.service.UserPreferenceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 검색은 user 원장이나 로그인 구현에 직접 접근하지 않고 이 경계를 사용한다. */
@Service
@Profile("!api-docs")
public class SearchUserAccessAdapter implements SearchUserAccess {
  private final CurrentActor actors;
  private final UserPreferenceService preferences;
  private final ObjectProvider<NotificationSubscriptions> subscriptions;

  public SearchUserAccessAdapter(
      CurrentActor actors,
      @Lazy UserPreferenceService preferences,
      ObjectProvider<NotificationSubscriptions> subscriptions) {
    this.actors = actors;
    this.preferences = preferences;
    this.subscriptions = subscriptions;
  }

  @Override
  public String customer(HttpServletRequest request, String csrfToken, boolean mutation) {
    // 세션/JWT 검증과 쿠키 변경 요청의 CSRF는 공통 HTTP 보안 필터에서 먼저 처리한다.
    // 역할은 토큰이나 오래된 로그인 세션 대신 현재 user 원장에서 다시 확인한다.
    var actor = actors.get();
    if (!"CUSTOMER".equals(actor.role()) && !"OWNER".equals(actor.role()))
      throw new SearchFailure(HttpStatus.FORBIDDEN, "CUSTOMER_REQUIRED");
    return actor.id();
  }

  @Override
  public boolean notificationsEnabled(String userId) {
    return subscriptions.getObject().hasActive(userId);
  }

  @Override
  public String explicitPreferences(String userId) {
    return preferences.explicitPreferences(userId);
  }

  @Override
  public java.util.List<String> personalizedCandidates(String after, int limit) {
    return preferences.personalizedCandidates(after, limit);
  }

  @Override
  public boolean personalizedNotificationsEnabled(String userId) {
    return preferences.personalizedNotificationsEnabled(userId);
  }
}
