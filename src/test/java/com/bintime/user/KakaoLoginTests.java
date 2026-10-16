package com.bintime.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.bintime.user.service.KakaoAccountService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "bintime.auth.client-id=test-client",
      "bintime.auth.client-secret=test-secret",
      "bintime.auth.redirect-uri={baseUrl}/api/v1/auth/oauth/kakao/callback",
      "bintime.auth.frontend-url=http://localhost:5173",
      "bintime.auth.allowed-return-paths=/,/reservations"
    })
@ActiveProfiles("test")
class KakaoLoginTests {
  @LocalServerPort private int port;
  @Autowired private JdbcClient jdbc;
  @Autowired private KakaoAccountService accounts;
  @Autowired private JsonMapper mapper;
  @MockitoBean private RestClientAuthorizationCodeTokenResponseClient kakaoTokenClient;
  @MockitoBean private DefaultOAuth2UserService kakaoUserInfoClient;
  private CookieManager cookies;
  private HttpClient browser;

  @BeforeEach
  void setUp() {
    jdbc.sql(
            """
            CREATE TABLE IF NOT EXISTS user_accounts (
              user_id VARCHAR(64) PRIMARY KEY,
              kakao_subject VARCHAR(100) UNIQUE,
              display_name VARCHAR(30) NOT NULL,
              role_code VARCHAR(8) DEFAULT 'CUSTOMER' NOT NULL
            )
            """)
        .update();
    jdbc.sql("DELETE FROM user_accounts").update();
    cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    when(kakaoTokenClient.getTokenResponse(any()))
        .thenReturn(
            OAuth2AccessTokenResponse.withToken("provider-access-token")
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .refreshToken("provider-refresh-token")
                .expiresIn(3600)
                .build());
    when(kakaoUserInfoClient.loadUser(any()))
        .thenReturn(
            new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")),
                Map.of("id", 42L, "properties", Map.of("nickname", "빈틈고객")),
                "id"));
    clearInvocations(kakaoTokenClient, kakaoUserInfoClient);
  }

  @Test
  void firstLoginCreatesAccountRotatesSessionAndDoesNotLeakProviderTokens() throws Exception {
    String state = start("/reservations");
    String oldSession = sessionId();
    var callback = callback("code=test-code&state=" + encode(state));
    assertThat(callback.statusCode()).isEqualTo(303);
    assertThat(callback.headers().firstValue("Location"))
        .contains("http://localhost:5173/reservations");
    assertThat(callback.body()).isEmpty();
    assertThat(callback.headers().toString())
        .doesNotContain("provider-access-token", "provider-refresh-token");
    assertThat(sessionId()).isNotEqualTo(oldSession);
    assertThat(callback.headers().allValues("Set-Cookie").toString())
        .contains("bt_session=", "HttpOnly", "SameSite=Lax");
    assertThat(jdbc.sql("SELECT COUNT(*) FROM user_accounts").query(Integer.class).single())
        .isEqualTo(1);
    assertThat(jdbc.sql("SELECT display_name FROM user_accounts").query(String.class).single())
        .isEqualTo("빈틈고객");
    var csrf = get("/api/v1/auth/csrf");
    assertThat(csrf.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(csrf.body());
    assertThat(body.at("/success/data/csrfToken").asText()).isNotBlank();
    assertThat(body.at("/success/data/expiresAt").asText()).isNotBlank();
    assertThat(body.at("/meta/path").asText()).isEqualTo("/api/v1/auth/csrf");
  }

  @Test
  void repeatLoginPreservesExistingAccountNameAndRole() throws Exception {
    jdbc.sql("INSERT INTO user_accounts VALUES ('owner-1', '42', '내가정한이름', 'OWNER')").update();
    assertThat(callback("code=test&state=" + encode(start("/"))).statusCode()).isEqualTo(303);
    assertThat(callback("code=test2&state=" + encode(start("/"))).statusCode()).isEqualTo(303);
    assertThat(jdbc.sql("SELECT COUNT(*) FROM user_accounts").query(Integer.class).single())
        .isEqualTo(1);
    assertThat(jdbc.sql("SELECT display_name FROM user_accounts").query(String.class).single())
        .isEqualTo("내가정한이름");
    assertThat(jdbc.sql("SELECT role_code FROM user_accounts").query(String.class).single())
        .isEqualTo("OWNER");
  }

  @Test
  void forgedStateAndReplayedCallbackCannotAuthenticate() throws Exception {
    String state = start("/");
    assertFailed(callback("code=test&state=forged"));
    verifyNoInteractions(kakaoTokenClient, kakaoUserInfoClient);
    assertFailed(callback("code=test&state=" + encode(state)));
    assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(401);

    String newState = start("/");
    assertThat(callback("code=test&state=" + encode(newState)).statusCode()).isEqualTo(303);
    clearInvocations(kakaoTokenClient, kakaoUserInfoClient);
    assertFailed(callback("code=test&state=" + encode(newState)));
    verifyNoInteractions(kakaoTokenClient, kakaoUserInfoClient);
  }

  @Test
  void deniedConsentAndTokenErrorsCreateNoAccount() throws Exception {
    assertFailed(callback("error=access_denied&state=" + encode(start("/"))));
    verifyNoInteractions(kakaoTokenClient, kakaoUserInfoClient);
    when(kakaoTokenClient.getTokenResponse(any()))
        .thenThrow(new OAuth2AuthenticationException(new OAuth2Error("invalid_grant")));
    assertFailed(callback("code=expired&state=" + encode(start("/"))));
    assertThat(jdbc.sql("SELECT COUNT(*) FROM user_accounts").query(Integer.class).single())
        .isZero();
  }

  @Test
  void loginDoesNotRequireOptionalNicknameOrEmailConsent() throws Exception {
    when(kakaoUserInfoClient.loadUser(any()))
        .thenReturn(
            new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", 42L), "id"));
    assertThat(callback("code=test&state=" + encode(start("/"))).statusCode()).isEqualTo(303);
    assertThat(jdbc.sql("SELECT display_name FROM user_accounts").query(String.class).single())
        .isEqualTo("빈틈고객");
  }

  @Test
  void invalidProviderIdentityCannotCreateAnAccount() throws Exception {
    when(kakaoUserInfoClient.loadUser(any()))
        .thenReturn(
            new DefaultOAuth2User(
                List.of(new SimpleGrantedAuthority("OAUTH2_USER")), Map.of("id", "invalid"), "id"));
    assertFailed(callback("code=test&state=" + encode(start("/"))));
    assertThat(jdbc.sql("SELECT COUNT(*) FROM user_accounts").query(Integer.class).single())
        .isZero();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "https://evil.example",
        "//evil.example",
        "/%2Fevil.example",
        "/\\evil.example",
        "/not-allowed",
        "/reservations?next=evil"
      })
  void returnToMustBeAnExplicitlyAllowedAppPath(String returnTo) throws Exception {
    var response = get("/api/v1/auth/oauth/kakao/start?returnTo=" + encode(returnTo));
    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(mapper.readTree(response.body()).at("/error/code").asText())
        .isEqualTo("INVALID_RETURN_TO");
    verifyNoInteractions(kakaoTokenClient, kakaoUserInfoClient);
  }

  @Test
  void logoutRequiresCsrfAndInvalidatesTheSession() throws Exception {
    assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(401);
    assertThat(get("/api/v1/users/me").statusCode()).isEqualTo(401);
    callback("code=test&state=" + encode(start("/")));
    String csrf =
        mapper.readTree(get("/api/v1/auth/csrf").body()).at("/success/data/csrfToken").asText();
    assertThat(logout(null).statusCode()).isEqualTo(403);
    assertThat(logout("wrong-token").statusCode()).isEqualTo(403);
    var queryOnly =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/logout?_csrf=" + encode(csrf)))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(queryOnly.statusCode()).isEqualTo(403);
    assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(200);
    var response = logout(csrf);
    assertThat(response.statusCode()).isEqualTo(204);
    assertThat(response.body()).isEmpty();
    assertThat(response.headers().allValues("Set-Cookie").toString())
        .contains("bt_session=", "Expires=Thu, 01 Jan 1970");
    assertThat(get("/api/v1/auth/csrf").statusCode()).isEqualTo(401);
  }

  @Test
  void concurrentFirstLoginsResolveToOneAccount() throws Exception {
    try (var executor = Executors.newFixedThreadPool(4)) {
      Callable<String> login = () -> accounts.login("99", "새고객").userId();
      var results = executor.invokeAll(List.of(login, login, login, login));
      String id = results.getFirst().get();
      for (var result : results) assertThat(result.get()).isEqualTo(id);
    }
    assertThat(jdbc.sql("SELECT COUNT(*) FROM user_accounts").query(Integer.class).single())
        .isEqualTo(1);
  }

  @Test
  void credentialedCorsAllowsOnlyTheConfiguredFrontend() throws Exception {
    var response =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                .header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
        .contains("http://localhost:5173");
    assertThat(response.headers().firstValue("Access-Control-Allow-Credentials")).contains("true");
    var denied =
        browser.send(
            HttpRequest.newBuilder(uri("/api/v1/auth/csrf"))
                .header("Origin", "https://evil.example")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(denied.statusCode()).isEqualTo(403);
  }

  private String start(String returnTo) throws Exception {
    var response = get("/api/v1/auth/oauth/kakao/start?returnTo=" + encode(returnTo));
    assertThat(response.statusCode()).isEqualTo(302);
    String location = response.headers().firstValue("Location").orElseThrow();
    assertThat(location)
        .startsWith("https://kauth.kakao.com/oauth/authorize?")
        .contains("client_id=test-client")
        .doesNotContain("test-secret");
    return UriUtils.decode(
        UriComponentsBuilder.fromUriString(location).build().getQueryParams().getFirst("state"),
        StandardCharsets.UTF_8);
  }

  private HttpResponse<String> callback(String query) throws Exception {
    return get("/api/v1/auth/oauth/kakao/callback?" + query);
  }

  private HttpResponse<String> get(String path) throws Exception {
    return browser.send(
        HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
  }

  private HttpResponse<String> logout(String token) throws Exception {
    var request =
        HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
            .POST(HttpRequest.BodyPublishers.noBody());
    if (token != null) request.header("X-CSRF-Token", token);
    return browser.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  private void assertFailed(HttpResponse<String> response) {
    assertThat(response.statusCode()).isEqualTo(303);
    assertThat(response.headers().firstValue("Location"))
        .contains("http://localhost:5173/login?error=OAUTH_FAILED");
    assertThat(response.body()).isEmpty();
  }

  private String sessionId() {
    return cookies.getCookieStore().getCookies().stream()
        .filter(cookie -> cookie.getName().equals("bt_session"))
        .findFirst()
        .orElseThrow()
        .getValue();
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
