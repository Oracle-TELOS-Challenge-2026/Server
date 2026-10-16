package com.bintime.common.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.service.UserAccountAccess;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;

class CurrentActorTests {
  private final UserAccountAccess users = mock(UserAccountAccess.class);
  private final MockEnvironment env = new MockEnvironment();
  private MockHttpServletRequest request;

  @BeforeEach
  void setup() {
    request = new MockHttpServletRequest();
    request.setMethod("GET");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    when(users.get("alice")).thenReturn(new Actor("alice", "CUSTOMER", "고객"));
  }

  @AfterEach
  void cleanup() {
    RequestContextHolder.resetRequestAttributes();
  }

  private void fails(String code) {
    assertThatThrownBy(() -> new CurrentActor(env, users).get())
        .isInstanceOf(ApiBusinessException.class)
        .hasMessage(code);
  }

  @Test
  void localIdentityRequiresAnExplicitFlagAndADevelopmentProfile() {
    env.setActiveProfiles("local");
    request.addHeader("X-Local-User-Id", "alice");
    fails("UNAUTHENTICATED");
    env.setProperty("bintime.auth.local-enabled", "true");
    assertThat(new CurrentActor(env, users).get().role()).isEqualTo("CUSTOMER");
  }

  @Test
  void productionNeverAcceptsLocalHeadersEvenWhenTestProfileIsAlsoActive() {
    env.setActiveProfiles("prod", "test");
    env.setProperty("bintime.auth.local-enabled", "true");
    request.addHeader("X-Local-User-Id", "alice");
    fails("UNAUTHENTICATED");
    verifyNoInteractions(users);
  }

  @Test
  void productionRequiresTrustedPrincipalIntegrationAndSessionCsrf() {
    env.setActiveProfiles("prod");
    request.setUserPrincipal(new Actor("alice", "OWNER", "untrusted role"));
    fails("AUTH_INTEGRATION_REQUIRED");
    env.setProperty("bintime.auth.production-enabled", "true");
    assertThat(new CurrentActor(env, users).get().role()).isEqualTo("CUSTOMER");
    request.setMethod("POST");
    fails("CSRF_INVALID");
    request.getSession().setAttribute("bintime.csrf-token", "server-token");
    request.addHeader("X-CSRF-Token", "wrong");
    fails("CSRF_INVALID");
    request.removeHeader("X-CSRF-Token");
    request.addHeader("X-CSRF-Token", "server-token");
    assertThat(new CurrentActor(env, users).get().id()).isEqualTo("alice");
  }
}
