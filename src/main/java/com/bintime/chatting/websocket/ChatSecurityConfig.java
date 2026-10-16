package com.bintime.chatting.websocket;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ResponseMeta;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.security.KakaoLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Profile("!api-docs")
public class ChatSecurityConfig {
  @Bean
  @Order(2)
  SecurityFilterChain chatSecurity(
      HttpSecurity http,
      HttpSessionCsrfTokenRepository repository,
      KakaoLoginProperties login,
      JsonMapper json)
      throws Exception {
    http.securityMatcher("/api/v1/chatting/**")
        // 채팅은 카카오 로그인 세션만 사용한다. 다른 도메인의 개발 헤더·JWT는 받지 않는다.
        .authorizeHttpRequests(
            auth ->
                auth.anyRequest()
                    .access(
                        (authentication, request) ->
                            new AuthorizationDecision(signedIn(authentication.get()))))
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(repository)
                    .csrfTokenRequestHandler(
                        new CsrfTokenRequestAttributeHandler() {
                          @Override
                          public String resolveCsrfTokenValue(
                              HttpServletRequest request, CsrfToken token) {
                            return request.getHeader(token.getHeaderName());
                          }
                        }))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            failure(json, request, response, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          boolean signedIn =
                              signedIn(SecurityContextHolder.getContext().getAuthentication());
                          failure(
                              json,
                              request,
                              response,
                              signedIn ? 403 : 401,
                              !signedIn
                                  ? "UNAUTHORIZED"
                                  : error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN");
                        }));
    if (login.corsEnabled()) {
      var cors = new CorsConfiguration();
      cors.setAllowedOrigins(List.of(login.frontendOrigin()));
      cors.setAllowedMethods(List.of("GET", "POST", "PUT", "OPTIONS"));
      cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token", "Idempotency-Key"));
      cors.setAllowCredentials(true);
      http.cors(config -> config.configurationSource(request -> cors));
    }
    return http.build();
  }

  private static boolean signedIn(Authentication authentication) {
    return authentication != null
        && authentication.isAuthenticated()
        && authentication.getPrincipal() instanceof BintimePrincipal;
  }

  private static void failure(
      JsonMapper json,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response
        .getWriter()
        .write(
            json.writeValueAsString(
                new ApiErrorResponse(
                    new ApiError(code, code, List.of(), false),
                    ResponseMeta.now(request.getRequestURI()))));
  }
}
