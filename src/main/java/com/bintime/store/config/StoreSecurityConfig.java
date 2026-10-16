package com.bintime.store.config;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ResponseMeta;
import com.bintime.user.security.KakaoLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.json.JsonMapper;

/** store만의 인증 경계. 기존 카카오 로그인과 같은 HttpSession 보안 컨텍스트를 사용한다. */
@Configuration(proxyBeanMethods = false)
@Profile("!api-docs")
public class StoreSecurityConfig {
  @Bean
  @Order(1)
  SecurityFilterChain storeSecurity(
      HttpSecurity http,
      Environment env,
      KakaoLoginProperties login,
      JsonMapper json,
      @Value("${bintime.auth.store-jwt.enabled:false}") boolean jwtEnabled)
      throws Exception {
    boolean local =
        env.acceptsProfiles(Profiles.of("local", "test"))
            && !env.acceptsProfiles(Profiles.of("prod"))
            && env.getProperty("bintime.auth.local-enabled", Boolean.class, false);
    var csrf = new HttpSessionCsrfTokenRepository();
    csrf.setHeaderName("X-CSRF-Token");
    http.securityMatcher("/api/v1/stores/**", "/api/v1/reservations/**", "/api/v1/owner/stores/**")
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/stores/categories",
                        "/api/v1/stores/{storeId}",
                        "/api/v1/stores/{storeId}/services",
                        "/api/v1/stores/{storeId}/slots",
                        "/api/v1/stores/{storeId}/slots/{slotId}")
                    .permitAll()
                    .anyRequest()
                    .access(
                        (authentication, context) -> {
                          var a = authentication.get();
                          return new AuthorizationDecision(
                              (local && context.getRequest().getHeader("X-Local-User-Id") != null)
                                  || (a != null
                                      && a.isAuthenticated()
                                      && !(a
                                          instanceof
                                          org.springframework.security.authentication
                                              .AnonymousAuthenticationToken)));
                        }))
        .csrf(
            config ->
                config
                    .csrfTokenRepository(csrf)
                    .csrfTokenRequestHandler(
                        new CsrfTokenRequestAttributeHandler() {
                          @Override
                          public String resolveCsrfTokenValue(
                              HttpServletRequest request, CsrfToken token) {
                            return request.getHeader(token.getHeaderName());
                          }
                        })
                    .ignoringRequestMatchers(
                        request ->
                            (local && request.getHeader("X-Local-User-Id") != null)
                                || (jwtEnabled
                                    && request.getHeader("Authorization") != null
                                    && request.getHeader("Authorization").startsWith("Bearer "))))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, error) ->
                            failure(json, request, response, 401, "UNAUTHENTICATED"))
                    .accessDeniedHandler(
                        (request, response, error) -> {
                          var a =
                              org.springframework.security.core.context.SecurityContextHolder
                                  .getContext()
                                  .getAuthentication();
                          boolean anonymous =
                              a == null
                                  || !a.isAuthenticated()
                                  || a
                                      instanceof
                                      org.springframework.security.authentication
                                          .AnonymousAuthenticationToken;
                          failure(
                              json,
                              request,
                              response,
                              anonymous ? 401 : 403,
                              anonymous
                                  ? "UNAUTHENTICATED"
                                  : error instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN");
                        }));
    if (jwtEnabled) {
      // JWT 발급자의 RS256 공개키·issuer·audience를 모두 고정하며 임의 공급자의 토큰을 받지 않는다.
      var decoder =
          NimbusJwtDecoder.withJwkSetUri(
                  env.getRequiredProperty("bintime.auth.store-jwt.jwk-set-uri"))
              .jwsAlgorithm(SignatureAlgorithm.RS256)
              .build();
      String issuer = env.getRequiredProperty("bintime.auth.store-jwt.issuer");
      String audience = env.getRequiredProperty("bintime.auth.store-jwt.audience");
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer),
              token ->
                  token.getExpiresAt() != null
                          && token.getSubject() != null
                          && !token.getSubject().isBlank()
                          && token.getAudience().contains(audience)
                      ? OAuth2TokenValidatorResult.success()
                      : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))));
      http.oauth2ResourceServer(
          resource ->
              resource
                  .jwt(jwt -> jwt.decoder(decoder))
                  .authenticationEntryPoint(
                      (request, response, error) ->
                          failure(json, request, response, 401, "INVALID_TOKEN")));
    }
    if (login.corsEnabled()) {
      var cors = new CorsConfiguration();
      cors.setAllowedOrigins(List.of(login.frontendOrigin()));
      cors.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
      cors.setAllowedHeaders(
          List.of("Content-Type", "X-CSRF-Token", "Authorization", "Idempotency-Key"));
      cors.setAllowCredentials(true);
      http.cors(config -> config.configurationSource(request -> cors));
    }
    return http.build();
  }

  private static void failure(
      JsonMapper json,
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
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
