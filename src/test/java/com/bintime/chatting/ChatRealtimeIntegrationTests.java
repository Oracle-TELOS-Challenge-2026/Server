package com.bintime.chatting;

import static org.assertj.core.api.Assertions.assertThat;

import com.bintime.chatting.controller.ChatMessageController;
import com.bintime.chatting.controller.ChatRoomController;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.chatting.websocket.ChatSecurityConfig;
import com.bintime.chatting.websocket.ChatWebSocket;
import com.bintime.chatting.websocket.ChatWebSocketConfig;
import com.bintime.common.web.ApiExceptionHandler;
import com.bintime.common.web.ApiResponseAdvice;
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
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    classes = ChatRealtimeIntegrationTests.Application.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.autoconfigure.exclude=org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration,org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
      "spring.data.jpa.repositories.enabled=false",
      "spring.datasource.url=jdbc:h2:mem:chat-realtime;MODE=Oracle;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:chatting-schema.sql",
      "management.endpoint.health.group.readiness.include=readinessState,db",
      "server.servlet.session.cookie.secure=false"
    })
@ActiveProfiles("chat-realtime-integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatRealtimeIntegrationTests {
  private static final String ORIGIN = "http://localhost:5173";
  @LocalServerPort int port;
  @Autowired JsonMapper json;
  @Autowired JdbcTemplate db;
  private final List<HttpClient> browsers = new ArrayList<>();
  private final List<WebSocket> sockets = new ArrayList<>();

  @BeforeEach
  void seed() {
    db.update("insert into user_accounts values('customer','고객','CUSTOMER')");
    db.update("insert into user_accounts values('owner','사장님','OWNER')");
    db.update("insert into store_shops values('shop','owner','빈틈 네일',null)");
  }

  @AfterEach
  void close() {
    sockets.forEach(WebSocket::abort);
    browsers.forEach(HttpClient::close);
  }

  @Test
  void committedRestAndSocketWritesBroadcastReadAndReplayThroughRealSpringWiring()
      throws Exception {
    var customer = login("customer");
    var owner = login("owner");
    var created =
        request(
            customer, "POST", "/api/v1/chatting/rooms", "create", Map.of("storeId", "shop"), 201);
    String room = created.path("id").asText();
    var customerSocket = connect(customer);
    var ownerSocket = connect(owner);
    subscribe(customerSocket, room, 0, 0);
    assertReadSnapshot(customerSocket, 0, 0);
    subscribe(ownerSocket, room, 0, 0);
    assertReadSnapshot(ownerSocket, 0, 0);

    var first =
        request(
            customer,
            "POST",
            "/api/v1/chatting/rooms/" + room + "/messages",
            "rest-first",
            Map.of("clientMessageId", "first", "content", "예약 문의입니다"),
            201);
    assertThat(frame(customerSocket, "message.created").path("payload").path("message"))
        .isEqualTo(first);
    assertThat(frame(ownerSocket, "message.created").path("payload").path("message"))
        .isEqualTo(first);
    assertThat(
            db.queryForObject(
                "select last_sequence from chatting_rooms where room_id=?", Long.class, room))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from chatting_messages where room_id=?", Long.class, room))
        .isEqualTo(1);

    send(
        ownerSocket,
        "message.send",
        Map.of("roomId", room, "clientMessageId", "second", "content", "네 가능합니다"));
    // An ACK and a broadcast may arrive in either order, but both describe one committed row.
    var ownerFrames = List.of(next(ownerSocket), next(ownerSocket));
    assertThat(ownerFrames)
        .extracting(value -> value.path("type").asText())
        .containsExactlyInAnyOrder("message.ack", "message.created");
    assertThat(ownerFrames)
        .allSatisfy(
            value ->
                assertThat(value.path("payload").path("message").path("sequence").asLong())
                    .isEqualTo(2));
    assertThat(
            frame(customerSocket, "message.created")
                .path("payload")
                .path("message")
                .path("sequence")
                .asLong())
        .isEqualTo(2);

    send(ownerSocket, "room.read", Map.of("roomId", room, "lastReadSequence", 2));
    var reads = List.of(next(ownerSocket), next(ownerSocket));
    assertThat(reads)
        .extracting(value -> value.path("type").asText())
        .containsExactlyInAnyOrder("room.read.ack", "room.read.updated");
    assertThat(
            frame(customerSocket, "room.read.updated")
                .path("payload")
                .path("lastReadSequence")
                .asLong())
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select last_read_sequence from chatting_read_positions where room_id=? and"
                    + " user_id='owner'",
                Long.class,
                room))
        .isEqualTo(2);

    customerSocket.socket().abort();
    request(
        owner,
        "POST",
        "/api/v1/chatting/rooms/" + room + "/messages",
        "while-offline",
        Map.of("clientMessageId", "third", "content", "내일 오세요"),
        201);
    assertThat(
            frame(ownerSocket, "message.created")
                .path("payload")
                .path("message")
                .path("sequence")
                .asLong())
        .isEqualTo(3);
    request(
        owner,
        "PUT",
        "/api/v1/chatting/rooms/" + room + "/read",
        "offline-read",
        Map.of("lastReadSequence", 3),
        200);
    assertThat(
            frame(ownerSocket, "room.read.updated")
                .path("payload")
                .path("lastReadSequence")
                .asLong())
        .isEqualTo(3);
    // A fresh HTTP session must restore the persisted peer cursor too.
    var relogged = login("customer");
    var rooms = request(relogged, "GET", "/api/v1/chatting/rooms", "unused", Map.of(), 200);
    assertThat(rooms.path("items").get(0).path("lastReadSequence").asLong(-1)).isZero();
    assertThat(rooms.path("items").get(0).path("peerReadSequence").asLong(-1)).isEqualTo(3);
    var reconnected = connect(relogged);
    subscribe(reconnected, room, 1, 3);
    assertThat(
            frame(reconnected, "message.created")
                .path("payload")
                .path("message")
                .path("sequence")
                .asLong())
        .isEqualTo(2);
    assertThat(
            frame(reconnected, "message.created")
                .path("payload")
                .path("message")
                .path("sequence")
                .asLong())
        .isEqualTo(3);
    assertReadSnapshot(reconnected, 0, 3);
    subscribe(reconnected, room, 3, 3);
    assertReadSnapshot(reconnected, 0, 3);
    assertThat(
            db.queryForObject(
                "select count(*) from chatting_messages where room_id=?", Long.class, room))
        .isEqualTo(3);
  }

  private Browser login(String user) throws Exception {
    var client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    browsers.add(client);
    var response =
        client.send(
            HttpRequest.newBuilder(uri("/test/session/login?user=" + user)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    return new Browser(client, json.readTree(response.body()).path("csrfToken").asText());
  }

  private JsonNode request(
      Browser browser, String method, String path, String key, Map<String, Object> body, int status)
      throws Exception {
    var request =
        HttpRequest.newBuilder(uri(path))
            .header("Content-Type", "application/json")
            .header("X-CSRF-Token", browser.csrf())
            .header("Idempotency-Key", key)
            .method(
                method,
                method.equals("GET")
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();
    var response = browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
    return json.readTree(response.body()).path("success").path("data");
  }

  private Socket connect(Browser browser) throws Exception {
    var listener = new Listener();
    var socket =
        browser
            .client()
            .newWebSocketBuilder()
            .header("Origin", ORIGIN)
            .subprotocols("bintime.chat.v1")
            .buildAsync(URI.create("ws://localhost:" + port + "/api/v1/chatting/ws"), listener)
            .join();
    sockets.add(socket);
    var connection = new Socket(socket, listener);
    send(connection, "connection.authenticate", Map.of("csrfToken", browser.csrf()));
    frame(connection, "connection.ready");
    return connection;
  }

  private void subscribe(Socket connection, String room, long after, long through)
      throws Exception {
    send(connection, "room.subscribe", Map.of("roomId", room, "afterSequence", after));
    assertThat(
            frame(connection, "room.subscribed")
                .path("payload")
                .path("replayThroughSequence")
                .asLong())
        .isEqualTo(through);
  }

  private void assertReadSnapshot(Socket connection, long customer, long owner) throws Exception {
    for (var expected : List.of(Map.entry("customer", customer), Map.entry("owner", owner))) {
      var payload = frame(connection, "room.read.updated").path("payload");
      assertThat(payload.path("userId").asText()).isEqualTo(expected.getKey());
      assertThat(payload.path("lastReadSequence").asLong(-1)).isEqualTo(expected.getValue());
      assertThat(payload.path("readAt").isNull()).isEqualTo(expected.getValue() == 0);
    }
  }

  private void send(Socket connection, String type, Map<String, Object> payload) {
    connection
        .socket()
        .sendText(
            json.writeValueAsString(
                Map.of("type", type, "requestId", "integration", "payload", payload)),
            true)
        .join();
  }

  private JsonNode frame(Socket connection, String type) throws Exception {
    var frame = next(connection);
    assertThat(frame.path("type").asText()).describedAs(frame.toString()).isEqualTo(type);
    return frame;
  }

  private JsonNode next(Socket connection) throws Exception {
    String value = connection.listener().messages.poll(5, TimeUnit.SECONDS);
    assertThat(value).describedAs("WebSocket frame within five seconds").isNotNull();
    return json.readTree(value);
  }

  private URI uri(String path) {
    return URI.create("http://localhost:" + port + path);
  }

  private record Browser(HttpClient client, String csrf) {}

  private record Socket(WebSocket socket, Listener listener) {}

  private static final class Listener implements WebSocket.Listener {
    final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
    final StringBuilder partial = new StringBuilder();

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
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("chat-realtime-integration")
  @EnableAutoConfiguration
  @Import({
    ChatRepository.class,
    ChatService.class,
    ChatRoomController.class,
    ChatMessageController.class,
    ApiResponseAdvice.class,
    ApiExceptionHandler.class,
    ChatWebSocket.class,
    ChatWebSocketConfig.class,
    ChatSecurityConfig.class
  })
  static class Application {
    @Bean
    com.bintime.store.contract.StoreReservationPort storeReservations() {
      return org.mockito.Mockito.mock(com.bintime.store.contract.StoreReservationPort.class);
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
  @Profile("chat-realtime-integration")
  static class TestSessionEndpoints {
    private final HttpSessionCsrfTokenRepository csrf;

    TestSessionEndpoints(HttpSessionCsrfTokenRepository csrf) {
      this.csrf = csrf;
    }

    @GetMapping("/test/session/login")
    Map<String, String> login(HttpServletRequest request, @RequestParam("user") String user) {
      var principal = new BintimePrincipal(user, "owner".equals(user) ? "OWNER" : "CUSTOMER");
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
  }
}
