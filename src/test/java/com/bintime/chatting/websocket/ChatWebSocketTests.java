package com.bintime.chatting.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.dto.ChatReadResponse;
import com.bintime.chatting.dto.SendChatMessageRequest;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import jakarta.servlet.http.HttpSessionEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ChatWebSocketTests {
  private static final BintimePrincipal ALICE = new BintimePrincipal("alice", "CUSTOMER");
  private final ChatService chats = mock(ChatService.class);
  private final JsonMapper json = JsonMapper.builder().build();
  private final MutableClock clock = new MutableClock();
  private final List<ChatMessageResponse> persisted = new CopyOnWriteArrayList<>();
  private final AtomicLong latest = new AtomicLong();
  private ChatWebSocket handler;
  private MockHttpSession http;
  private Socket socket;

  @BeforeEach
  void setUp() throws Exception {
    http = new MockHttpSession();
    http.setMaxInactiveInterval(0);
    authenticateHttpSession(ALICE);
    when(chats.latest(anyString(), anyString())).thenAnswer(invocation -> latest.get());
    when(chats.replay(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
        .thenAnswer(
            invocation ->
                persisted.stream()
                    .filter(
                        message ->
                            message.sequence() > (long) invocation.getArgument(2)
                                && message.sequence() <= (long) invocation.getArgument(3))
                    .sorted(Comparator.comparingLong(ChatMessageResponse::sequence))
                    .limit((Integer) invocation.getArgument(4))
                    .toList());
    handler = new ChatWebSocket(chats, json, clock);
    socket = open("first");
  }

  @AfterEach
  void tearDown() {
    handler.shutdown();
  }

  @Test
  void firstFrameMustAuthenticateWithSessionCsrfToken() {
    send(socket, "room.subscribe", "request-1", Map.of("roomId", "room", "afterSequence", 0));

    assertThat(socket.closeStatus.getCode()).isEqualTo(4403);
    assertThat(frames(socket))
        .singleElement()
        .satisfies(
            frame -> {
              assertThat(frame.path("type").asText()).isEqualTo("error");
              assertThat(frame.path("payload").path("code").asText()).isEqualTo("CSRF_INVALID");
            });
    verify(chats, never()).latest(anyString(), anyString());
  }

  @Test
  void wrongCsrfTokenClosesWithoutGrantingAccess() {
    send(socket, "connection.authenticate", "auth", Map.of("csrfToken", "other-session-token"));

    assertThat(socket.closeStatus.getCode()).isEqualTo(4403);
    assertThat(types(socket)).containsExactly("error");
  }

  @Test
  void successfulAuthenticationUsesRawWebSocketContract() {
    authenticate(socket);

    assertThat(types(socket)).containsExactly("connection.ready");
    JsonNode frame = frames(socket).getFirst();
    assertThat(frame.path("requestId").asText()).isEqualTo("authenticate");
    assertThat(frame.path("payload").path("userId").asText()).isEqualTo("alice");
    assertThat(frame.path("payload").path("heartbeatSeconds").asInt()).isEqualTo(25);
    assertThat(frame.has("resultType")).isFalse();
    assertThat(socket.closeStatus).isNull();
    verify(socket.session).setTextMessageSizeLimit(16 * 1024);
  }

  @Test
  void tomcatBlockingWritesHaveAFiveSecondTimeout() throws Exception {
    var standard = mock(StandardWebSocketSession.class);
    var nativeSession = mock(jakarta.websocket.Session.class);
    var properties = new HashMap<String, Object>();
    var attributes = socket.session.getAttributes();
    when(standard.getId()).thenReturn("native-socket");
    when(standard.getAttributes()).thenReturn(attributes);
    when(standard.getNativeSession()).thenReturn(nativeSession);
    when(nativeSession.getUserProperties()).thenReturn(properties);

    handler.afterConnectionEstablished(standard);

    assertThat(properties)
        .containsEntry("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5000L);
  }

  @Test
  void requestIdLimitCountsCharactersRatherThanUtf8Bytes() {
    authenticate(socket);
    String requestId = "요".repeat(64);
    send(socket, "ping", requestId, Map.of());

    assertThat(lastFrame(socket).path("type").asText()).isEqualTo("pong");
    assertThat(lastFrame(socket).path("requestId").asText()).isEqualTo(requestId);
    send(socket, "ping", requestId + "청", Map.of());
    assertThat(lastFrame(socket).path("payload").path("code").asText()).isEqualTo("INVALID_FRAME");
  }

  @Test
  void authenticationAtDeadlineCannotRaceTheTimeoutCheck() {
    clock.advance(Duration.ofSeconds(5));
    authenticate(socket);

    assertThat(socket.closeStatus.getCode()).isEqualTo(4403);
    assertThat(types(socket)).doesNotContain("connection.ready");
  }

  @Test
  void unauthenticatedSocketExpiresAfterFiveSeconds() throws Exception {
    clock.advance(Duration.ofMillis(5001));
    handler.tick();

    pollUntil(() -> socket.closeStatus != null);
    assertThat(socket.closeStatus.getCode()).isEqualTo(4403);
  }

  @Test
  void subscribeRejectsOtherUsersRoomWithoutLeakingMessages() {
    authenticate(socket);
    when(chats.latest("alice", "foreign-room"))
        .thenThrow(new ApiBusinessException(404, "ROOM_NOT_FOUND"));

    send(socket, "room.subscribe", "foreign", Map.of("roomId", "foreign-room", "afterSequence", 0));

    assertThat(lastFrame(socket).path("payload").path("code").asText()).isEqualTo("ROOM_NOT_FOUND");
    assertThat(socket.closeStatus).isNull();
    verify(chats, never()).replay(anyString(), anyString(), anyLong(), anyLong(), anyInt());
  }

  @Test
  void replayAndOutOfOrderCommitCallbacksDeliverPersistedMessagesOnceInSequence() throws Exception {
    authenticate(socket);
    persisted.addAll(List.of(message(1), message(2)));
    latest.set(2);
    subscribe(socket, 0);
    assertThat(types(socket))
        .containsExactly(
            "connection.ready", "room.subscribed", "message.created", "message.created");
    assertThat(frames(socket).get(1).path("payload").path("replayThroughSequence").asLong())
        .isEqualTo(2);

    persisted.addAll(List.of(message(3), message(4)));
    latest.set(4);
    handler.messageCommitted(new ChatService.MessageCommitted(message(4)));
    handler.messageCommitted(new ChatService.MessageCommitted(message(3)));
    handler.messageCommitted(new ChatService.MessageCommitted(message(4)));
    pollUntil(() -> deliveredSequences(socket).size() == 4);

    assertThat(deliveredSequences(socket)).containsExactly(1L, 2L, 3L, 4L);
  }

  @Test
  void messageCommittedDuringReplayJoinsLiveDeliveryWithoutAGap() throws Exception {
    authenticate(socket);
    persisted.add(message(1));
    latest.set(1);
    AtomicBoolean firstReplay = new AtomicBoolean(true);
    when(chats.replay(anyString(), anyString(), anyLong(), anyLong(), anyInt()))
        .thenAnswer(
            invocation -> {
              long after = invocation.getArgument(2);
              long through = invocation.getArgument(3);
              if (firstReplay.compareAndSet(true, false)) {
                persisted.add(message(2));
                latest.set(2);
                handler.messageCommitted(new ChatService.MessageCommitted(message(2)));
              }
              return persisted.stream()
                  .filter(message -> message.sequence() > after && message.sequence() <= through)
                  .toList();
            });

    subscribe(socket, 0);
    pollUntil(() -> deliveredSequences(socket).size() == 2);

    assertThat(deliveredSequences(socket)).containsExactly(1L, 2L);
  }

  @Test
  void commitBurstDuringSlowSendDoesNotBlockOtherSocketsOrLoseMessages() throws Exception {
    authenticate(socket);
    subscribe(socket, 0);
    Socket fast = open("fast-peer");
    authenticate(fast);
    subscribe(fast, 0);
    var blocked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              WebSocketMessage<?> outgoing = invocation.getArgument(0);
              if (outgoing instanceof TextMessage text
                  && text.getPayload().contains("message.created")) {
                if (blocked.getCount() > 0) {
                  blocked.countDown();
                  assertThat(release.await(3, TimeUnit.SECONDS)).isTrue();
                }
              }
              socket.sent.add(outgoing);
              return null;
            })
        .when(socket.session)
        .sendMessage(any());
    persisted.add(message(1));
    latest.set(1);
    handler.messageCommitted(new ChatService.MessageCommitted(message(1)));
    assertThat(blocked.await(3, TimeUnit.SECONDS)).isTrue();
    try {
      persisted.add(message(2));
      latest.set(2);
      for (int duplicate = 0; duplicate < 100; duplicate++) {
        handler.messageCommitted(new ChatService.MessageCommitted(message(2)));
        handler.tick();
      }
      pollUntil(() -> deliveredSequences(fast).size() == 2);
      assertThat(deliveredSequences(fast)).containsExactly(1L, 2L);
    } finally {
      release.countDown();
    }
    pollUntil(() -> deliveredSequences(socket).size() == 2);
    assertThat(deliveredSequences(socket)).containsExactly(1L, 2L);
  }

  @Test
  void reconnectReplaysOnlyMessagesAfterAcknowledgedCursor() throws Exception {
    authenticate(socket);
    persisted.addAll(List.of(message(1), message(2), message(3)));
    latest.set(3);
    subscribe(socket, 0);
    handler.afterConnectionClosed(socket.session, CloseStatus.NORMAL);
    Socket reconnect = open("reconnect");
    authenticate(reconnect);

    subscribe(reconnect, 2);

    assertThat(deliveredSequences(reconnect)).containsExactly(3L);
  }

  @Test
  void replayBeyondOneThousandRequiresRestCatchupAndDoesNotSubscribe() throws Exception {
    authenticate(socket);
    latest.set(1001);
    subscribe(socket, 0);

    assertThat(types(socket)).containsExactly("connection.ready", "room.resync_required");
    assertThat(lastFrame(socket).path("payload").path("latestSequence").asLong()).isEqualTo(1001);
    verify(chats, never()).replay(anyString(), anyString(), anyLong(), anyLong(), anyInt());

    // A subsequent event cannot silently start delivery until the client explicitly resubscribes.
    handler.messageCommitted(new ChatService.MessageCommitted(message(1001)));
    send(socket, "ping", "barrier", Map.of());
    assertThat(deliveredSequences(socket)).isEmpty();
  }

  @Test
  void duplicateMessageRetriesReturnSamePersistedAck() {
    authenticate(socket);
    var request = new SendChatMessageRequest("client-1", "hello");
    when(chats.send("alice", "room", request)).thenReturn(message(1));

    send(
        socket,
        "message.send",
        "attempt-1",
        Map.of("roomId", "room", "clientMessageId", "client-1", "content", "hello"));
    send(
        socket,
        "message.send",
        "attempt-2",
        Map.of("roomId", "room", "clientMessageId", "client-1", "content", "hello"));

    var acks =
        frames(socket).stream()
            .filter(frame -> "message.ack".equals(frame.path("type").asText()))
            .toList();
    assertThat(acks).hasSize(2);
    assertThat(acks.getFirst().path("payload").path("message"))
        .isEqualTo(acks.getLast().path("payload").path("message"));
    assertThat(acks.getLast().path("requestId").asText()).isEqualTo("attempt-2");
    assertThat(deliveredSequences(socket)).isEmpty();
  }

  @Test
  void repeatedRateLimitViolationsCloseWith4429() {
    authenticate(socket);
    when(chats.send(anyString(), anyString(), any(SendChatMessageRequest.class)))
        .thenThrow(new ApiBusinessException(429, "RATE_LIMITED"));

    for (int attempt = 0; attempt < 3; attempt++) {
      send(
          socket,
          "message.send",
          "attempt-" + attempt,
          Map.of("roomId", "room", "clientMessageId", "client-" + attempt, "content", "hello"));
    }

    assertThat(socket.closeStatus.getCode()).isEqualTo(4429);
    assertThat(lastFrame(socket).path("payload").path("retryable").asBoolean()).isTrue();
    assertThat(lastFrame(socket).path("payload").path("retryAfterSeconds").asInt()).isEqualTo(1);
  }

  @Test
  void oversizeUtf8FrameClosesWith1009() {
    authenticate(socket);
    send(
        socket,
        "message.send",
        "oversize",
        Map.of("roomId", "room", "clientMessageId", "client", "content", "한".repeat(6000)));

    assertThat(socket.closeStatus.getCode()).isEqualTo(1009);
    assertThat(lastFrame(socket).path("payload").path("code").asText())
        .isEqualTo("FRAME_TOO_LARGE");
    verify(chats, never()).send(anyString(), anyString(), any(SendChatMessageRequest.class));
  }

  @Test
  void malformedJsonDoesNotCloseAnAuthenticatedConnection() {
    authenticate(socket);
    handler.handleTextMessage(socket.session, new TextMessage("{"));
    send(socket, "ping", "still-open", Map.of());

    assertThat(types(socket)).containsExactly("connection.ready", "error", "pong");
    assertThat(frames(socket).get(1).path("payload").path("code").asText())
        .isEqualTo("INVALID_FRAME");
    assertThat(socket.closeStatus).isNull();
  }

  @Test
  void subscriptionLimitDoesNotEvictExistingRooms() {
    authenticate(socket);
    for (int room = 0; room < 21; room++) {
      send(
          socket,
          "room.subscribe",
          "subscribe-" + room,
          Map.of("roomId", "room-" + room, "afterSequence", 0));
    }

    assertThat(types(socket).stream().filter("room.subscribed"::equals)).hasSize(20);
    assertThat(lastFrame(socket).path("payload").path("code").asText())
        .isEqualTo("SUBSCRIPTION_LIMIT");
    send(socket, "room.unsubscribe", "unsubscribe", Map.of("roomId", "room-0"));
    send(socket, "room.subscribe", "replacement", Map.of("roomId", "room-20", "afterSequence", 0));
    assertThat(lastFrame(socket).path("type").asText()).isEqualTo("room.subscribed");
  }

  @Test
  void sessionInvalidationStopsFurtherCommandsAndLiveDelivery() {
    authenticate(socket);
    http.invalidate();
    send(socket, "ping", "expired", Map.of());

    assertThat(socket.closeStatus.getCode()).isEqualTo(4401);
    assertThat(types(socket)).containsExactly("connection.ready");
  }

  @Test
  void sessionDestroyedClosesAllItsSocketsImmediately() throws Exception {
    authenticate(socket);
    Socket another = open("another-tab");
    authenticate(another);

    handler.sessionDestroyed(new HttpSessionEvent(http));

    assertThat(socket.closeStatus.getCode()).isEqualTo(4401);
    assertThat(another.closeStatus.getCode()).isEqualTo(4401);
  }

  @Test
  void accountSwitchClosesSocketBeforeTheNextCommand() {
    authenticate(socket);
    authenticateHttpSession(new BintimePrincipal("bob", "CUSTOMER"));
    send(socket, "ping", "changed-account", Map.of());

    assertThat(socket.closeStatus.getCode()).isEqualTo(4401);
    assertThat(types(socket)).containsExactly("connection.ready");
  }

  @Test
  void heartbeatPongKeepsTransportAliveWithoutRenewingHttpSession() throws Exception {
    authenticate(socket);
    long lastHttpAccess = http.getLastAccessedTime();
    clock.advance(Duration.ofSeconds(25));
    handler.tick();
    pollUntil(() -> socket.sent.stream().anyMatch(PingMessage.class::isInstance));
    handler.handlePongMessage(socket.session, new PongMessage());
    clock.advance(Duration.ofSeconds(40));
    handler.tick();
    send(socket, "ping", "alive", Map.of());

    assertThat(lastFrame(socket).path("type").asText()).isEqualTo("pong");
    assertThat(http.getLastAccessedTime()).isEqualTo(lastHttpAccess);
    assertThat(socket.closeStatus).isNull();
  }

  @Test
  void missingHeartbeatResponseClosesAfterSixtySeconds() throws Exception {
    authenticate(socket);
    clock.advance(Duration.ofSeconds(60));
    handler.tick();

    pollUntil(() -> socket.closeStatus != null);
    assertThat(socket.closeStatus.getCode()).isEqualTo(1001);
    assertThat(socket.closeStatus.getReason()).isEqualTo("HEARTBEAT_TIMEOUT");
  }

  @Test
  void outOfOrderReadCallbacksDoNotRegressLastReadSequence() throws Exception {
    authenticate(socket);
    latest.set(9);
    subscribe(socket, 9);
    AtomicInteger checked = new AtomicInteger();
    when(chats.latest("alice", "room"))
        .thenAnswer(
            invocation -> {
              checked.incrementAndGet();
              return latest.get();
            });
    handler.readCommitted(
        new ChatService.ReadCommitted(
            "bob", new ChatReadResponse("room", 9L, OffsetDateTime.now(clock))));
    pollUntil(() -> types(socket).contains("room.read.updated"));
    handler.readCommitted(
        new ChatService.ReadCommitted(
            "bob", new ChatReadResponse("room", 4L, OffsetDateTime.now(clock).minusSeconds(1))));
    handler.readCommitted(
        new ChatService.ReadCommitted(
            "bob", new ChatReadResponse("room", 9L, OffsetDateTime.now(clock))));
    pollUntil(() -> checked.get() >= 2);
    // Acquiring the connection lock after the final access check waits for its send decision.
    send(socket, "ping", "read-barrier", Map.of());

    var updates =
        frames(socket).stream()
            .filter(frame -> "room.read.updated".equals(frame.path("type").asText()))
            .map(frame -> frame.path("payload").path("lastReadSequence").asLong())
            .toList();
    assertThat(updates).containsExactly(9L);
  }

  private void authenticateHttpSession(BintimePrincipal principal) {
    http.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
  }

  private Socket open(String id) throws Exception {
    Socket value = new Socket(id);
    handler.afterConnectionEstablished(value.session);
    return value;
  }

  private void authenticate(Socket target) {
    send(target, "connection.authenticate", "authenticate", Map.of("csrfToken", "csrf-token"));
  }

  private void subscribe(Socket target, long after) {
    send(target, "room.subscribe", "subscribe", Map.of("roomId", "room", "afterSequence", after));
  }

  private void send(Socket target, String type, String request, Map<String, ?> payload) {
    handler.handleTextMessage(
        target.session,
        new TextMessage(
            json.writeValueAsString(
                Map.of("type", type, "requestId", request, "payload", payload))));
  }

  private List<JsonNode> frames(Socket target) {
    return target.sent.stream()
        .filter(TextMessage.class::isInstance)
        .map(message -> json.readTree(((TextMessage) message).getPayload()))
        .toList();
  }

  private List<String> types(Socket target) {
    return frames(target).stream().map(frame -> frame.path("type").asText()).toList();
  }

  private JsonNode lastFrame(Socket target) {
    return frames(target).getLast();
  }

  private List<Long> deliveredSequences(Socket target) {
    return frames(target).stream()
        .filter(frame -> "message.created".equals(frame.path("type").asText()))
        .map(frame -> frame.path("payload").path("message").path("sequence").asLong())
        .toList();
  }

  private ChatMessageResponse message(long sequence) {
    return new ChatMessageResponse(
        "message-" + sequence,
        "room",
        sequence,
        new ChatMessageResponse.Sender("bob", "OWNER", "Owner"),
        "client-" + sequence,
        "TEXT",
        "Message " + sequence,
        OffsetDateTime.now(clock));
  }

  private static void pollUntil(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private final class Socket {
    final WebSocketSession session = mock(WebSocketSession.class);
    final List<WebSocketMessage<?>> sent = new CopyOnWriteArrayList<>();
    final AtomicBoolean open = new AtomicBoolean(true);
    volatile CloseStatus closeStatus;

    Socket(String id) throws Exception {
      var attributes = new HashMap<String, Object>();
      attributes.put("principal", ALICE);
      attributes.put("httpSession", http);
      attributes.put("csrfToken", "csrf-token");
      when(session.getId()).thenReturn(id);
      when(session.getAttributes()).thenReturn(attributes);
      when(session.isOpen()).thenAnswer(invocation -> open.get());
      doAnswer(
              invocation -> {
                sent.add(invocation.getArgument(0));
                return null;
              })
          .when(session)
          .sendMessage(any());
      doAnswer(
              invocation -> {
                closeStatus = invocation.getArgument(0);
                open.set(false);
                return null;
              })
          .when(session)
          .close(any(CloseStatus.class));
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicLong millis =
        new AtomicLong(Instant.parse("2026-10-16T12:00:00Z").toEpochMilli());

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis.get());
    }

    void advance(Duration duration) {
      millis.addAndGet(duration.toMillis());
    }
  }
}
