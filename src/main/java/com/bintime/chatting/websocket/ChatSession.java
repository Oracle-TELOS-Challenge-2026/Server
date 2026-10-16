package com.bintime.chatting.websocket;

import com.bintime.user.security.BintimePrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

final class ChatSession {
  private ChatSession() {}

  static boolean valid(HttpSession session, BintimePrincipal principal, Clock clock) {
    if (session == null || principal == null) return false;
    try {
      var value =
          session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      if (!(value instanceof SecurityContext context)
          || context.getAuthentication() == null
          || !context.getAuthentication().isAuthenticated()
          || !principal.equals(context.getAuthentication().getPrincipal())) return false;
      int seconds = session.getMaxInactiveInterval();
      // Reading a WebSocket session must not extend the HTTP session's idle expiry.
      return seconds <= 0 || clock.millis() - session.getLastAccessedTime() < seconds * 1000L;
    } catch (IllegalStateException expired) {
      return false;
    }
  }
}
