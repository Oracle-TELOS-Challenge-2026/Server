package com.bintime.chatting.websocket;

import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.security.KakaoLoginProperties;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.http.server.*;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@Profile("!api-docs")
public class ChatWebSocketConfig implements WebSocketConfigurer {
  private final ChatWebSocket handler;
  private final KakaoLoginProperties login;
  private final HttpSessionCsrfTokenRepository csrf;
  private final Clock clock;

  public ChatWebSocketConfig(
      ChatWebSocket handler,
      KakaoLoginProperties login,
      HttpSessionCsrfTokenRepository csrf,
      Clock clock) {
    this.handler = handler;
    this.login = login;
    this.csrf = csrf;
    this.clock = clock;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry
        .addHandler(handler, "/api/v1/chatting/ws")
        .setAllowedOrigins(login.frontendOrigin())
        .addInterceptors(
            new HandshakeInterceptor() {
              @Override
              public boolean beforeHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler ignored,
                  Map<String, Object> attributes) {
                if (!(request instanceof ServletServerHttpRequest servlet))
                  return reject(response, HttpStatus.UNAUTHORIZED);
                if (!login.frontendOrigin().equals(request.getHeaders().getOrigin()))
                  return reject(response, HttpStatus.FORBIDDEN);
                String protocols = request.getHeaders().getFirst("Sec-WebSocket-Protocol");
                if (protocols == null
                    || Arrays.stream(protocols.split(","))
                        .map(String::trim)
                        .noneMatch("bintime.chat.v1"::equals))
                  return reject(response, HttpStatus.BAD_REQUEST);
                HttpSession session = servlet.getServletRequest().getSession(false);
                if (session == null) return reject(response, HttpStatus.UNAUTHORIZED);
                Object value =
                    session.getAttribute(
                        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
                if (!(value instanceof SecurityContext context)
                    || context.getAuthentication() == null
                    || !(context.getAuthentication().getPrincipal()
                        instanceof BintimePrincipal principal)
                    || !ChatSession.valid(session, principal, clock))
                  return reject(response, HttpStatus.UNAUTHORIZED);
                var token = csrf.loadToken(servlet.getServletRequest());
                if (token == null) return reject(response, HttpStatus.FORBIDDEN);
                attributes.put("httpSession", session);
                attributes.put("principal", principal);
                attributes.put("csrfToken", token.getToken());
                return true;
              }

              @Override
              public void afterHandshake(
                  ServerHttpRequest request,
                  ServerHttpResponse response,
                  WebSocketHandler ignored,
                  Exception error) {}
            });
  }

  private static boolean reject(ServerHttpResponse response, HttpStatus status) {
    response.setStatusCode(status);
    return false;
  }

  @Bean
  ServletListenerRegistrationBean<ChatWebSocket> chatSessionListener() {
    return new ServletListenerRegistrationBean<>(handler);
  }
}
