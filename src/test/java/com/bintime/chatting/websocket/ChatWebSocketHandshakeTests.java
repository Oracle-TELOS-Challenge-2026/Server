package com.bintime.chatting.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bintime.chatting.service.ChatService;
import com.bintime.user.security.BintimePrincipal;
import com.bintime.user.security.KakaoLoginProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = ChatWebSocketHandshakeTests.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
      "management.endpoint.health.group.readiness.include=readinessState",
      "server.servlet.session.cookie.secure=false"
    })
@ActiveProfiles("chat-ws-integration")
class ChatWebSocketHandshakeTests {
  private static final String ORIGIN = "http://localhost:5173";
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired ChatService chats;
  private HttpClient browser;
  private final List<WebSocket> connections = new ArrayList<>();

  @BeforeEach
  void setUp() {
    var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    browser = HttpClient.newBuilder().cookieHandler(cookies).build();
    when(chats.latest(anyString(), anyString())).thenReturn(0L);
  }

  @AfterEach
  void closeSockets() {
    connections.forEach(WebSocket::abort);
    browser.close();
  }

  @Test
  void realUpgradeRequiresSessionCookie() {
    assertHandshakeRejected(browser, ORIGIN, "bintime.chat.v1", 401);
  }

  @Test
  void realUpgradeRejectsForeignOrigin() throws Exception {
    login();
    assertHandshakeRejected(browser, "https://foreign.example", "bintime.chat.v1", 403);
  }

  @Test
  void realUpgradeRequiresChatSubprotocol() throws Exception {
    login();
    assertHandshakeRejected(browser, ORIGIN, "other.protocol", 400);
  }

  @Test
  void realConnectionAuthenticatesAndClosesOnHttpSessionInvalidation() throws Exception {
    String csrf = login();
    var listener = new Listener();
    var socket = connect(browser, ORIGIN, "bintime.chat.v1", listener);
    assertThat(socket.getSubprotocol()).isEqualTo("bintime.chat.v1");
    socket
        .sendText(
            json.writeValueAsString(
                Map.of(
                    "type",
                    "connection.authenticate",
                    "requestId",
                    "auth",
                    "payload",
                    Map.of("csrfToken", csrf))),
            true)
        .join();

    JsonNode ready = json.readTree(listener.messages.poll(3, TimeUnit.SECONDS));
    assertThat(ready.path("type").asText()).isEqualTo("connection.ready");
    assertThat(ready.path("payload").path("userId").asText()).isEqualTo("alice");
    assertThat(ready.has("resultType")).isFalse();

    browser.send(
        HttpRequest.newBuilder(uri("/test/session/logout")).GET().build(),
        HttpResponse.BodyHandlers.discarding());
    // Tomcat closes its authenticated sockets before application session listeners run.
    assertThat(listener.closed.get(3, TimeUnit.SECONDS)).isEqualTo(1008);
  }

  @Test
  void realSocketRejectsWrongFirstFrameCsrf() throws Exception {
    login();
    var listener = new Listener();
    var socket = connect(browser, ORIGIN, "bintime.chat.v1", listener);
    socket
        .sendText(
            "{\"type\":\"connection.authenticate\",\"payload\":{\"csrfToken\":\"wrong\"}}", true)
        .join();

    JsonNode error = json.readTree(listener.messages.poll(3, TimeUnit.SECONDS));
    assertThat(error.path("payload").path("code").asText()).isEqualTo("CSRF_INVALID");
    assertThat(listener.closed.get(3, TimeUnit.SECONDS)).isEqualTo(4403);
  }

  private String login() throws Exception {
    var response =
        browser.send(
            HttpRequest.newBuilder(uri("/test/session/login")).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return json.readTree(response.body()).path("csrfToken").asText();
  }

  private WebSocket connect(HttpClient client, String origin, String protocol, Listener listener) {
    var socket =
        client
            .newWebSocketBuilder()
            .header("Origin", origin)
            .subprotocols(protocol)
            .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chatting/ws"), listener)
            .join();
    connections.add(socket);
    return socket;
  }

  private void assertHandshakeRejected(
      HttpClient client, String origin, String protocol, int status) {
    assertThatThrownBy(() -> connect(client, origin, protocol, new Listener()))
        .isInstanceOf(CompletionException.class)
        .cause()
        .isInstanceOfSatisfying(
            WebSocketHandshakeException.class,
            failure -> assertThat(failure.getResponse().statusCode()).isEqualTo(status));
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private static final class Listener implements WebSocket.Listener {
    final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final CompletableFuture<Integer> closed = new CompletableFuture<>();
    private final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket socket) {
      socket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        messages.add(partial.toString());
        partial.setLength(0);
      }
      socket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
      closed.complete(status);
      return null;
    }

    @Override
    public void onError(WebSocket socket, Throwable failure) {
      closed.completeExceptionally(failure);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("chat-ws-integration")
  @EnableAutoConfiguration
  @Import({ChatWebSocket.class, ChatWebSocketConfig.class, ChatSecurityConfig.class})
  static class Application {
    @Bean
    ChatService chats() {
      return org.mockito.Mockito.mock(ChatService.class);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrf() {
      return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    KakaoLoginProperties login() {
      return new KakaoLoginProperties("", "", "", ORIGIN, List.of("/"), true);
    }

    @Bean
    @Order(100)
    SecurityFilterChain testEndpoints(HttpSecurity http) throws Exception {
      return http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
          .csrf(csrf -> csrf.disable())
          .build();
    }

    @Bean
    TestSessionEndpoints sessions(HttpSessionCsrfTokenRepository csrf) {
      return new TestSessionEndpoints(csrf);
    }
  }

  @RestController
  @Profile("chat-ws-integration")
  static class TestSessionEndpoints {
    private final HttpSessionCsrfTokenRepository csrf;

    TestSessionEndpoints(HttpSessionCsrfTokenRepository csrf) {
      this.csrf = csrf;
    }

    @GetMapping("/test/session/login")
    Map<String, String> login(HttpServletRequest request) {
      var principal = new BintimePrincipal("alice", "CUSTOMER");
      request
          .getSession()
          .setAttribute(
              HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
              new SecurityContextImpl(
                  new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
      var token = csrf.generateToken(request);
      csrf.saveToken(token, request, null);
      return Map.of("csrfToken", token.getToken());
    }

    @GetMapping("/test/session/logout")
    void logout(HttpServletRequest request) {
      request.getSession().invalidate();
    }
  }
}
