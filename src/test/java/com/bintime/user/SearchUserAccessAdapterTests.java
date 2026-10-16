package com.bintime.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.common.auth.Actor;
import com.bintime.common.auth.CurrentActor;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.adapter.SearchUserAccessAdapter;
import com.bintime.user.service.UserPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;

class SearchUserAccessAdapterTests {
  @Test
  void usesTheSharedAuthenticationBoundaryAndCurrentCustomerRole() {
    var actors = mock(CurrentActor.class);
    var access = new SearchUserAccessAdapter(actors, mock(UserPreferenceService.class), null);
    var request = new MockHttpServletRequest();
    when(actors.get()).thenReturn(new Actor("customer", "OWNER", "owner"));
    assertThat(access.customer(request, null, false)).isEqualTo("customer");
    assertThat(access.customer(request, null, true)).isEqualTo("customer");
    when(actors.get()).thenReturn(new Actor("customer", "UNKNOWN", "unknown"));
    assertThatThrownBy(() -> access.customer(request, null, false)).hasMessage("CUSTOMER_REQUIRED");
    when(actors.get()).thenReturn(new Actor("customer", "CUSTOMER", "name"));
    assertThat(access.customer(request, null, false)).isEqualTo("customer");
  }

  @Test
  void notificationEligibilityUsesActiveSessionSubscriptionsAndPreservesPreferenceContract() {
    var preferences = mock(UserPreferenceService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<NotificationSubscriptions> provider = mock(ObjectProvider.class);
    var subscriptions = mock(NotificationSubscriptions.class);
    when(provider.getObject()).thenReturn(subscriptions);
    var access = new SearchUserAccessAdapter(mock(CurrentActor.class), preferences, provider);
    when(subscriptions.hasActive("customer")).thenReturn(true, false);
    assertThat(access.notificationsEnabled("customer")).isTrue();
    assertThat(access.notificationsEnabled("customer")).isFalse();
    when(preferences.explicitPreferences("customer")).thenReturn("{\"text\":\"원문\"}");
    assertThat(access.explicitPreferences("customer")).isEqualTo("{\"text\":\"원문\"}");
  }
}
