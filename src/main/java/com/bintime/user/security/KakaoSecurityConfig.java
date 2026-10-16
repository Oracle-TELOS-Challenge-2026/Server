package com.bintime.user.security;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ResponseMeta;
import com.bintime.user.adapter.KakaoOAuth2UserService;
import com.bintime.user.service.KakaoAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.cors.CorsConfiguration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(KakaoLoginProperties.class)
public class KakaoSecurityConfig {
  private static final Logger log = LoggerFactory.getLogger(KakaoSecurityConfig.class);

  @Bean
  @Profile("api-docs")
  SecurityFilterChain documentationSecurity(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(AbstractHttpConfigurer::disable)
        .build();
  }

  @Bean
  @Profile("!api-docs")
  DefaultOAuth2UserService kakaoUserInfoClient() {
    RestTemplate rest = new RestTemplate(requestFactory());
    rest.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
    var service = new DefaultOAuth2UserService();
    service.setRestOperations(rest);
    return service;
  }

  @Bean
  @Profile("!api-docs")
  RestClientAuthorizationCodeTokenResponseClient kakaoTokenClient() {
    var client = new RestClientAuthorizationCodeTokenResponseClient();
    client.setRestClient(
        RestClient.builder()
            .requestFactory(requestFactory())
            .configureMessageConverters(
                converters -> {
                  converters.disableDefaults();
                  converters.addCustomConverter(new FormHttpMessageConverter());
                  converters.addCustomConverter(
                      new OAuth2AccessTokenResponseHttpMessageConverter());
                })
            .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
            .build());
    return client;
  }

  @Bean
  @Profile("!api-docs")
  SecurityFilterChain apiSecurity(
      HttpSecurity http,
      KakaoLoginProperties properties,
      KakaoAccountService accounts,
      DefaultOAuth2UserService kakaoUserInfoClient,
      RestClientAuthorizationCodeTokenResponseClient kakaoTokenClient,
      JsonMapper mapper)
      throws Exception {
    String frontend = properties.frontendOrigin();
    var csrfRepository = new HttpSessionCsrfTokenRepository();
    csrfRepository.setHeaderName("X-CSRF-Token");
    http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/v1/auth/oauth/kakao/start", "/api/v1/auth/oauth/kakao/callback")
                    .permitAll()
                    .requestMatchers("/api/v1/auth/**", "/api/v1/users/**")
                    .authenticated()
                    // 다른 도메인의 501 계약 골격은 각 기능 구현 시 인증·권한을 적용한다.
                    .anyRequest()
                    .permitAll())
        .csrf(
            csrf ->
                csrf.csrfTokenRepository(csrfRepository)
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
                            !request.getServletPath().startsWith("/api/v1/auth/")
                                && !request.getServletPath().startsWith("/api/v1/users/")))
        .requestCache(AbstractHttpConfigurer::disable)
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            failure(mapper, request, response, 401, "UNAUTHORIZED"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            failure(
                                mapper,
                                request,
                                response,
                                403,
                                exception instanceof CsrfException ? "CSRF_INVALID" : "FORBIDDEN")))
        .logout(
            logout ->
                logout
                    .logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("bt_session")
                    .logoutSuccessHandler(
                        (request, response, authentication) -> response.setStatus(204)));

    // 배포 Nginx가 CORS를 처리한다. 양쪽에서 헤더를 추가하면 브라우저가 응답을 거부한다.
    if (properties.corsEnabled()) {
      var cors = new CorsConfiguration();
      cors.setAllowedOrigins(List.of(frontend));
      cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
      cors.setAllowedHeaders(List.of("Content-Type", "X-CSRF-Token", "Last-Event-ID"));
      cors.setAllowCredentials(true);
      http.cors(config -> config.configurationSource(request -> cors));
    }

    if (properties.configured()) {
      var registration =
          ClientRegistration.withRegistrationId("kakao")
              .clientId(properties.clientId())
              .clientSecret(properties.clientSecret())
              .clientAuthenticationMethod(
                  properties.clientSecret() == null || properties.clientSecret().isBlank()
                      ? ClientAuthenticationMethod.NONE
                      : ClientAuthenticationMethod.CLIENT_SECRET_POST)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri(properties.redirectUri())
              .authorizationUri("https://kauth.kakao.com/oauth/authorize")
              .tokenUri("https://kauth.kakao.com/oauth/token")
              .userInfoUri("https://kapi.kakao.com/v2/user/me")
              .userNameAttributeName("id")
              .clientName("Kakao")
              .build();
      var registrations = new InMemoryClientRegistrationRepository(registration);
      var clients = new HttpSessionOAuth2AuthorizedClientRepository();
      http.oauth2Login(
          oauth ->
              oauth
                  .clientRegistrationRepository(registrations)
                  .authorizedClientRepository(clients)
                  .loginPage("/api/v1/auth/oauth/kakao/start")
                  .authorizationEndpoint(
                      endpoint ->
                          endpoint.authorizationRequestResolver(
                              new KakaoAuthorizationRequestResolver(registrations, properties)))
                  .redirectionEndpoint(
                      endpoint -> endpoint.baseUri("/api/v1/auth/oauth/kakao/callback"))
                  .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(kakaoTokenClient))
                  .userInfoEndpoint(
                      endpoint ->
                          endpoint.userService(
                              new KakaoOAuth2UserService(kakaoUserInfoClient, accounts)))
                  .successHandler(
                      (request, response, authentication) -> {
                        var session = request.getSession();
                        String returnTo =
                            properties.returnPath(
                                (String)
                                    session.getAttribute(
                                        KakaoAuthorizationRequestResolver.RETURN_TO));
                        session.removeAttribute(KakaoAuthorizationRequestResolver.RETURN_TO);
                        // 로그인 후 카카오 API를 쓰지 않으므로 공급자 토큰은 보관하지 않는다.
                        clients.removeAuthorizedClient("kakao", authentication, request, response);
                        response.setStatus(303);
                        response.setHeader("Location", frontend + returnTo);
                      })
                  .failureHandler(
                      (request, response, exception) -> {
                        log.warn(
                            "Kakao login failed: {}",
                            exception instanceof OAuth2AuthenticationException oauthError
                                ? oauthError.getError().getErrorCode()
                                : exception.getClass().getSimpleName());
                        new SecurityContextLogoutHandler().logout(request, response, null);
                        new CookieClearingLogoutHandler("bt_session")
                            .logout(request, response, null);
                        response.setStatus(303);
                        response.setHeader("Location", frontend + "/login?error=OAUTH_FAILED");
                      })
                  .withObjectPostProcessor(
                      new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
                        @Override
                        public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(
                            O filter) {
                          filter.setAuthenticationFailureHandler(
                              (request, response, exception) ->
                                  failure(mapper, request, response, 400, "INVALID_RETURN_TO"));
                          return filter;
                        }
                      }));
    }
    return http.build();
  }

  private static JdkClientHttpRequestFactory requestFactory() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    return factory;
  }

  private static void failure(
      JsonMapper mapper,
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
            mapper.writeValueAsString(
                new ApiErrorResponse(
                    new ApiError(code, code, List.of(), false),
                    ResponseMeta.now(request.getRequestURI()))));
  }
}
