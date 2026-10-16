package com.bintime.chatting.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import com.bintime.user.security.BintimePrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

class ChatSessionTests {
  private final BintimePrincipal principal = new BintimePrincipal("alice", "CUSTOMER");

  @Test
  void requiresAuthenticatedMatchingKakaoPrincipal() {
    var session = session();
    var now = clock(session.getLastAccessedTime());

    assertThat(ChatSession.valid(session, principal, now)).isTrue();
    assertThat(ChatSession.valid(session, new BintimePrincipal("bob", "CUSTOMER"), now)).isFalse();
    assertThat(ChatSession.valid(null, principal, now)).isFalse();
    assertThat(ChatSession.valid(session, null, now)).isFalse();
    session.removeAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
    assertThat(ChatSession.valid(session, principal, now)).isFalse();
  }

  @Test
  void unauthenticatedContextIsNotAccepted() {
    var session = session();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(new TestingAuthenticationToken(principal, null)));

    assertThat(ChatSession.valid(session, principal, clock(session.getLastAccessedTime())))
        .isFalse();
  }

  @Test
  void idleDeadlineIsEnforcedWithoutUpdatingLastAccessTime() {
    var session = session();
    session.setMaxInactiveInterval(60);
    long last = session.getLastAccessedTime();

    assertThat(ChatSession.valid(session, principal, clock(last + 59_999))).isTrue();
    assertThat(ChatSession.valid(session, principal, clock(last + 60_000))).isFalse();
    assertThat(session.getLastAccessedTime()).isEqualTo(last);
  }

  @Test
  void invalidatedSessionIsRejectedWithoutThrowing() {
    var session = session();
    session.invalidate();

    assertThat(ChatSession.valid(session, principal, Clock.systemUTC())).isFalse();
  }

  @Test
  void sessionWithNoIdleTimeoutRemainsValid() {
    var session = session();
    session.setMaxInactiveInterval(0);

    assertThat(
            ChatSession.valid(session, principal, clock(session.getLastAccessedTime() + 86400000)))
        .isTrue();
  }

  private MockHttpSession session() {
    var session = new MockHttpSession();
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    return session;
  }

  private Clock clock(long millis) {
    return Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC);
  }
}
