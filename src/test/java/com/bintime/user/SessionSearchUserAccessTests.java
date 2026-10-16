package com.bintime.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.common.auth.Actor;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.adapter.SessionSearchUserAccess;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.service.UserAccountAccess;
import com.bintime.user.service.UserPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

class SessionSearchUserAccessTests {
  @Test
  void usesDatabaseRoleAndTheSameSessionCsrfToken() {
    var accounts = mock(UserAccountAccess.class);
    var preferences = mock(UserPreferenceService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<NotificationSubscriptions> subscriptions = mock(ObjectProvider.class);
    var csrf = new HttpSessionCsrfTokenRepository();
    var access = new SessionSearchUserAccess(accounts, preferences, subscriptions, csrf);
    var request = new MockHttpServletRequest();
    assertThatThrownBy(() -> access.customer(request, null, false))
        .isInstanceOf(ApiBusinessException.class)
        .hasMessage("UNAUTHORIZED");
    var principal = new BintimePrincipal("customer", "CUSTOMER");
    request.setUserPrincipal(
        new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao"));
    request.getSession();
    when(accounts.get("customer")).thenReturn(new Actor("customer", "OWNER", "owner"));
    assertThatThrownBy(() -> access.customer(request, null, false)).hasMessage("FORBIDDEN");
    when(accounts.get("customer")).thenReturn(new Actor("customer", "CUSTOMER", "name"));
    assertThat(access.customer(request, null, false)).isEqualTo("customer");
    assertThatThrownBy(() -> access.customer(request, "forged", true)).hasMessage("CSRF_INVALID");
    var token = csrf.generateToken(request);
    csrf.saveToken(token, request, null);
    assertThat(access.customer(request, token.getToken(), true)).isEqualTo("customer");
  }

  @Test
  void notificationEligibilityUsesActiveSessionSubscriptionsAndPreservesPreferenceContract() {
    var preferences = mock(UserPreferenceService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<NotificationSubscriptions> provider = mock(ObjectProvider.class);
    var subscriptions = mock(NotificationSubscriptions.class);
    when(provider.getObject()).thenReturn(subscriptions);
    var access =
        new SessionSearchUserAccess(
            mock(UserAccountAccess.class),
            preferences,
            provider,
            new HttpSessionCsrfTokenRepository());
    when(subscriptions.hasActive("customer")).thenReturn(true, false);
    assertThat(access.notificationsEnabled("customer")).isTrue();
    assertThat(access.notificationsEnabled("customer")).isFalse();
    when(preferences.explicitPreferences("customer")).thenReturn("{\"text\":\"원문\"}");
    assertThat(access.explicitPreferences("customer")).isEqualTo("{\"text\":\"원문\"}");
  }
}
