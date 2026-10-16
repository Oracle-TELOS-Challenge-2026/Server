package com.bintime.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.notification.dto.PushSubscriptionResponse;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.security.BintimePrincipal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "bintime.auth.client-id=", "bintime.auth.client-secret=",
      "bintime.auth.frontend-url=http://localhost:5173", "bintime.fcm.enabled=false",
      "notification.scheduling-enabled=false"
    })
@ActiveProfiles("test")
class NotificationSecurityTests {
  private static final String PUSH = "/api/v1/notifications/push-subscriptions";
  @Autowired WebApplicationContext context;
  @Autowired JsonMapper json;
  @MockitoBean NotificationSubscriptions subscriptions;
  MockMvc mvc;
  MockHttpSession session;
  String token;

  @BeforeEach
  void setup() throws Exception {
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    var principal = new BintimePrincipal("customer", "CUSTOMER");
    session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    var response =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    token = json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
  }

  @Test
  void missingSessionAndCsrfAreRejectedBeforeRegistration() throws Exception {
    mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
    mvc.perform(
            post(PUSH)
                .servletPath(PUSH)
                .contentType("application/json")
                .content("{\"installationId\":\"fid\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(PUSH)
                .servletPath(PUSH)
                .session(session)
                .contentType("application/json")
                .content("{\"installationId\":\"fid\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("CSRF_INVALID"));
    verify(subscriptions, never()).register(any(), any(), any());
  }

  @Test
  void registrationUsesAuthenticatedIdentityAndPreservesObjectEnvelope() throws Exception {
    var subscription = new PushSubscriptionResponse("push", OffsetDateTime.now(), null);
    when(subscriptions.register(any(), same(session), eq("fid")))
        .thenReturn(
            new NotificationSubscriptions.Registration(subscription, true),
            new NotificationSubscriptions.Registration(subscription, false));
    for (int status : new int[] {201, 200}) {
      mvc.perform(
              post(PUSH)
                  .servletPath(PUSH)
                  .session(session)
                  .header("X-CSRF-Token", token)
                  .contentType("application/json")
                  .content("{\"installationId\":\"fid\"}"))
          .andExpect(status().is(status))
          .andExpect(jsonPath("$.success.data.id").value("push"))
          .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.nullValue()));
    }
    verify(subscriptions, times(2))
        .register(eq(new BintimePrincipal("customer", "CUSTOMER")), same(session), eq("fid"));
  }

  @Test
  void allowedBrowserPreflightAndDeleteUseExistingCsrfContract() throws Exception {
    mvc.perform(
            options(PUSH)
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    mvc.perform(
            options(PUSH)
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
    mvc.perform(
            delete(PUSH + "/push")
                .servletPath(PUSH + "/push")
                .session(session)
                .header("X-CSRF-Token", token))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));
    verify(subscriptions).delete(new BintimePrincipal("customer", "CUSTOMER"), "push");
  }

  @Test
  void logoutReleasesSubscriptionWhileSessionIsStillValid() throws Exception {
    doAnswer(
            invocation -> {
              assertThat(session.isInvalid()).isFalse();
              return null;
            })
        .when(subscriptions)
        .releaseSession(session);
    mvc.perform(
            post("/api/v1/auth/logout")
                .servletPath("/api/v1/auth/logout")
                .session(session)
                .header("X-CSRF-Token", token))
        .andExpect(status().isNoContent());
    verify(subscriptions).releaseSession(session);
    assertThat(session.isInvalid()).isTrue();
  }
}
