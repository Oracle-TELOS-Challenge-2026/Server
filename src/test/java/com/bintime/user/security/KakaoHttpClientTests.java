package com.bintime.user.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;

class KakaoHttpClientTests {
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void tokenFormAndUserInfoBearerRequestsMatchKakaoProtocol(boolean secretEnabled)
      throws Exception {
    var tokenForm = new AtomicReference<Map<String, String>>();
    var authorization = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/oauth/token",
        exchange -> {
          String form =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          tokenForm.set(
              Stream.of(form.split("&"))
                  .map(value -> value.split("=", 2))
                  .collect(Collectors.toMap(pair -> decode(pair[0]), pair -> decode(pair[1]))));
          byte[] body =
              """
              {"access_token":"test-access","refresh_token":"test-refresh","token_type":"bearer","expires_in":3600}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.createContext(
        "/v2/user/me",
        exchange -> {
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body =
              """
              {"id":42,"properties":{"nickname":"빈틈고객"}}
              """
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      String redirect = "http://localhost:8080/api/v1/auth/oauth/kakao/callback";
      var registration =
          ClientRegistration.withRegistrationId("kakao")
              .clientId("test-client")
              .clientSecret(secretEnabled ? "test-secret" : "")
              .clientAuthenticationMethod(
                  secretEnabled
                      ? ClientAuthenticationMethod.CLIENT_SECRET_POST
                      : ClientAuthenticationMethod.NONE)
              .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
              .redirectUri(redirect)
              .authorizationUri(origin + "/oauth/authorize")
              .tokenUri(origin + "/oauth/token")
              .userInfoUri(origin + "/v2/user/me")
              .userNameAttributeName("id")
              .build();
      var request =
          OAuth2AuthorizationRequest.authorizationCode()
              .authorizationUri(origin + "/oauth/authorize")
              .clientId("test-client")
              .redirectUri(redirect)
              .state("test-state")
              .build();
      var response =
          OAuth2AuthorizationResponse.success("one-time-code")
              .redirectUri(redirect)
              .state("test-state")
              .build();
      var config = new KakaoSecurityConfig();
      var tokens =
          config
              .kakaoTokenClient()
              .getTokenResponse(
                  new OAuth2AuthorizationCodeGrantRequest(
                      registration, new OAuth2AuthorizationExchange(request, response)));
      assertThat(tokenForm.get())
          .containsEntry("client_id", "test-client")
          .containsEntry("grant_type", "authorization_code")
          .containsEntry("redirect_uri", redirect)
          .containsEntry("code", "one-time-code");
      if (secretEnabled) assertThat(tokenForm.get()).containsEntry("client_secret", "test-secret");
      else assertThat(tokenForm.get()).doesNotContainKey("client_secret");
      var user =
          config
              .kakaoUserInfoClient()
              .loadUser(new OAuth2UserRequest(registration, tokens.getAccessToken()));
      assertThat(authorization.get()).isEqualTo("Bearer test-access");
      assertThat(user.getName()).isEqualTo("42");
      assertThat((Map<?, ?>) user.getAttributes().get("properties"))
          .isEqualTo(Map.of("nickname", "빈틈고객"));
    } finally {
      server.stop(0);
    }
  }

  private static String decode(String value) {
    return URLDecoder.decode(value, StandardCharsets.UTF_8);
  }
}
