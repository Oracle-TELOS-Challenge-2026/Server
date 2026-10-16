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
    org.springframework.security.core.context.SecurityContextHolder.clearContext();
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
  void sessionAuthenticationUsesCurrentDatabaseRole() {
    env.setActiveProfiles("prod");
    request.setUserPrincipal(new Actor("alice", "OWNER", "untrusted role"));
    fails("UNAUTHENTICATED");
    var principal = new com.bintime.user.security.BintimePrincipal("alice", "OWNER");
    org.springframework.security.core.context.SecurityContextHolder.getContext()
        .setAuthentication(
            new org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "kakao"));
    assertThat(new CurrentActor(env, users).get().role()).isEqualTo("CUSTOMER");
  }
}
