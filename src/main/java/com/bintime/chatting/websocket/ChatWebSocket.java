package com.bintime.chatting.websocket;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.*;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;
import org.springframework.web.socket.handler.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
@Profile("!api-docs")
public class ChatWebSocket extends TextWebSocketHandler
    implements SubProtocolCapable, HttpSessionListener {
  private final ChatService chats;
  private final JsonMapper json;
  private final Clock clock;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();
  private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

  public ChatWebSocket(ChatService chats, JsonMapper json, Clock clock) {
    this.chats = chats;
    this.json = json;
    this.clock = clock;
    timer.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
  }

  @Override
  public List<String> getSubProtocols() {
    return List.of("bintime.chat.v1");
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    var principal = (BintimePrincipal) session.getAttributes().get("principal");
    var http = (HttpSession) session.getAttributes().get("httpSession");
    if (!ChatSession.valid(http, principal, clock)) {
      session.close(new CloseStatus(4401, "SESSION_EXPIRED"));
      return;
    }
    session.setTextMessageSizeLimit(16 * 1024);
    if (session instanceof StandardWebSocketSession standard) {
      // The app runs on Tomcat; bound the blocking write itself, not only a decorator's queue.
      standard
          .getNativeSession()
          .getUserProperties()
          .put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", 5000L);
    }
    var connection =
        new Connection(
            new ConcurrentWebSocketSessionDecorator(session, 10_000, 64 * 1024),
            principal,
            http,
            clock.millis());
    connections.put(session.getId(), connection);
  }

  @Override
  public void handleTextMessage(WebSocketSession session, TextMessage message) {
    var c = connections.get(session.getId());
    if (c == null) return;
    synchronized (c) {
      String requestId = null;
      try {
        if (!valid(c)) return;
        c.lastInbound = clock.millis();
        require(
            message.getPayload().getBytes(StandardCharsets.UTF_8).length <= 16 * 1024,
            400,
            "FRAME_TOO_LARGE");
        JsonNode frame = json.readTree(message.getPayload());
        require(frame != null && frame.isObject(), 400, "INVALID_FRAME");
        JsonNode request = frame.get("requestId");
        if (request != null && !request.isNull()) {
          require(
              request.isString()
                  && !request.asText().isBlank()
                  && request.asText().length() <= 64
                  && request.asText().chars().noneMatch(Character::isISOControl),
              400,
              "INVALID_FRAME");
          requestId = request.asText();
        }
        JsonNode payload = frame.get("payload");
        require(payload != null && payload.isObject(), 400, "INVALID_FRAME");
        String type = text(frame, "type");
        if (!c.authenticated) {
          require(
              "connection.authenticate".equals(type) && clock.millis() - c.opened < 5000,
              403,
              "CSRF_INVALID");
          String token = text(payload, "csrfToken");
          Object expected = session.getAttributes().get("csrfToken");
          require(
              expected instanceof String
                  && MessageDigest.isEqual(
                      ((String) expected).getBytes(StandardCharsets.UTF_8),
                      token.getBytes(StandardCharsets.UTF_8)),
              403,
              "CSRF_INVALID");
          c.authenticated = true;
          send(
              c,
              "connection.ready",
              requestId,
              new ChatWebSocketFrame.Ready(c.principal.userId(), 25));
          return;
        }
        switch (type) {
          case "room.subscribe" ->
              subscribe(c, requestId, text(payload, "roomId"), number(payload, "afterSequence"));
          case "room.unsubscribe" -> {
            String room = text(payload, "roomId");
            ChatService.id(room);
            unsubscribe(c, room);
            send(c, "room.unsubscribed", requestId, new ChatWebSocketFrame.Room(room));
          }
          case "message.send" -> {
            var saved =
                chats.send(
                    c.principal.userId(),
                    text(payload, "roomId"),
                    new SendChatMessageRequest(
                        text(payload, "clientMessageId"), text(payload, "content")));
            c.rateViolations = 0;
            send(c, "message.ack", requestId, new ChatWebSocketFrame.Message(saved));
          }
          case "room.read" -> {
            var read =
                chats.read(
                    c.principal.userId(),
                    text(payload, "roomId"),
                    number(payload, "lastReadSequence"));
            send(
                c,
                "room.read.ack",
                requestId,
                new ChatWebSocketFrame.ReadAck(
                    read.roomId(), read.lastReadSequence(), read.readAt()));
          }
          case "ping" ->
              send(c, "pong", requestId, new ChatWebSocketFrame.Pong(OffsetDateTime.now(clock)));
          case "pong" -> {
            /* JSON peers may answer a heartbeat explicitly. */
          }
          default -> throw new ApiBusinessException(400, "UNKNOWN_FRAME_TYPE");
        }
      } catch (ApiBusinessException error) {
        send(
            c,
            "error",
            requestId,
            new ChatWebSocketFrame.Error(
                error.code,
                error.code,
                error.status == 429 || error.status >= 500,
                error.status == 429 ? 1 : null));
        if (!c.authenticated) close(c, 4403, "AUTHENTICATION_REQUIRED");
        else if (error.status == 429 && ++c.rateViolations >= 3) close(c, 4429, "RATE_LIMITED");
        else if (error.status == 401) close(c, 4401, "SESSION_EXPIRED");
        else if ("FRAME_TOO_LARGE".equals(error.code)) close(c, 1009, "FRAME_TOO_LARGE");
      } catch (tools.jackson.core.JacksonException | IllegalArgumentException invalid) {
        send(
            c,
            "error",
            requestId,
            new ChatWebSocketFrame.Error("INVALID_FRAME", "INVALID_FRAME", false, null));
        if (!c.authenticated) close(c, 4403, "AUTHENTICATION_REQUIRED");
      } catch (RuntimeException failure) {
        send(
            c,
            "error",
            requestId,
            new ChatWebSocketFrame.Error("SERVICE_UNAVAILABLE", "SERVICE_UNAVAILABLE", true, null));
      }
    }
  }

  private void subscribe(Connection c, String request, String room, long after) {
    long latest = chats.latest(c.principal.userId(), room);
    require(after >= 0 && after <= latest, 400, "INVALID_SEQUENCE");
    require(
        c.subscriptions.containsKey(room) || c.subscriptions.size() < 20,
        400,
        "SUBSCRIPTION_LIMIT");
    unsubscribe(c, room);
    if (latest - after > 1000) {
      send(c, "room.resync_required", request, new ChatWebSocketFrame.ResyncRequired(room, latest));
      return;
    }
    c.subscriptions.put(room, after);
    c.readSequences.put(room, new HashMap<>());
    send(c, "room.subscribed", request, new ChatWebSocketFrame.Subscribed(room, latest));
    // Publish the subscription before replay; draining from its DB cursor joins replay to live
    // data.
    drain(c, room);
  }

  private void drain(Connection c, String room) {
    synchronized (c) {
      if (!valid(c) || !c.authenticated || !c.subscriptions.containsKey(room)) return;
      try {
        long through = chats.latest(c.principal.userId(), room);
        long after = c.subscriptions.get(room);
        if (through - after > 1000) {
          unsubscribe(c, room);
          send(
              c,
              "room.resync_required",
              null,
              new ChatWebSocketFrame.ResyncRequired(room, through));
          return;
        }
        for (var message : chats.replay(c.principal.userId(), room, after, through, 1000)) {
          if (!valid(c)) return;
          send(c, "message.created", null, new ChatWebSocketFrame.Message(message));
          if (!connections.containsKey(c.socket.getId())) return;
          c.subscriptions.put(room, message.sequence());
        }
      } catch (ApiBusinessException failure) {
        unsubscribe(c, room);
        send(
            c,
            "error",
            null,
            new ChatWebSocketFrame.Error(failure.code, failure.code, false, null));
      } catch (RuntimeException unavailable) {
        close(c, 1011, "SERVICE_UNAVAILABLE");
      }
    }
  }

  // shortcut: broadcasts are local to one app; add a shared broker before running multiple apps.
  @TransactionalEventListener
  public void messageCommitted(ChatService.MessageCommitted event) {
    for (var c : connections.values()) {
      String room = event.message().roomId();
      if (!c.subscriptions.containsKey(room)) continue;
      c.dirtyRooms.add(room);
      schedule(c);
    }
  }

  @TransactionalEventListener
  public void readCommitted(ChatService.ReadCommitted event) {
    for (var c : connections.values()) {
      if (!c.subscriptions.containsKey(event.read().roomId())) continue;
      c.pendingReads.merge(
          new ReadKey(event.read().roomId(), event.userId()),
          event,
          (old, next) ->
              old.read().lastReadSequence() >= next.read().lastReadSequence() ? old : next);
      schedule(c);
    }
  }

  private void readUpdate(Connection c, ChatService.ReadCommitted event) {
    if (!valid(c) || !c.authenticated || !c.subscriptions.containsKey(event.read().roomId()))
      return;
    try {
      chats.latest(c.principal.userId(), event.read().roomId());
      var positions = c.readSequences.get(event.read().roomId());
      long sequence = event.read().lastReadSequence();
      // Commit callbacks can arrive out of order; never regress the peer's read cursor.
      if (sequence <= positions.getOrDefault(event.userId(), 0L)) return;
      send(
          c,
          "room.read.updated",
          null,
          new ChatWebSocketFrame.ReadUpdated(
              event.read().roomId(), event.userId(), sequence, event.read().readAt()));
      positions.put(event.userId(), sequence);
    } catch (ApiBusinessException failure) {
      unsubscribe(c, event.read().roomId());
    } catch (RuntimeException unavailable) {
      close(c, 1011, "SERVICE_UNAVAILABLE");
    }
  }

  @Override
  public void handlePongMessage(WebSocketSession session, PongMessage message) {
    var c = connections.get(session.getId());
    if (c != null) c.lastInbound = clock.millis();
  }

  void tick() {
    for (var c : connections.values()) schedule(c);
  }

  private void maintain(Connection c) {
    if (!valid(c)) return;
    long now = clock.millis();
    if (!c.authenticated && now - c.opened >= 5000) {
      close(c, 4403, "AUTHENTICATION_REQUIRED");
      return;
    }
    if (now - c.lastInbound >= 60_000) {
      close(c, 1001, "HEARTBEAT_TIMEOUT");
      return;
    }
    if (c.authenticated && now - c.lastPing >= 25_000) {
      try {
        c.socket.sendMessage(new PingMessage());
        c.lastPing = now;
      } catch (IOException | RuntimeException failed) {
        close(c, 1011, "SEND_FAILED");
      }
    }
  }

  private void schedule(Connection c) {
    c.workPending.set(true);
    if (!c.workerRunning.compareAndSet(false, true)) return;
    try {
      senders.execute(
          () -> {
            try {
              // One worker per socket coalesces notifications while a slow write is in progress.
              while (c.workPending.getAndSet(false) && connections.containsKey(c.socket.getId())) {
                synchronized (c) {
                  maintain(c);
                  for (String room : c.dirtyRooms.toArray(String[]::new)) {
                    if (c.dirtyRooms.remove(room)) drain(c, room);
                  }
                  for (var entry : c.pendingReads.entrySet()) {
                    if (c.pendingReads.remove(entry.getKey(), entry.getValue()))
                      readUpdate(c, entry.getValue());
                  }
                }
              }
            } finally {
              c.workerRunning.set(false);
              if (c.workPending.get() && connections.containsKey(c.socket.getId())) schedule(c);
            }
          });
    } catch (RejectedExecutionException shutdown) {
      c.workerRunning.set(false);
    }
  }

  private static void unsubscribe(Connection c, String room) {
    c.subscriptions.remove(room);
    c.readSequences.remove(room);
  }

  private boolean valid(Connection c) {
    if (!connections.containsKey(c.socket.getId())) return false;
    if (!ChatSession.valid(c.http, c.principal, clock)) {
      close(c, 4401, "SESSION_EXPIRED");
      return false;
    }
    return true;
  }

  private void send(Connection c, String type, String request, ChatWebSocketFrame.Payload payload) {
    try {
      if (c.socket.isOpen())
        c.socket.sendMessage(
            new TextMessage(
                json.writeValueAsString(new ChatWebSocketFrame(type, request, payload))));
      else close(c, 1001, "CLOSED");
    } catch (IOException | RuntimeException failed) {
      close(c, 1011, "SEND_FAILED");
    }
  }

  private void close(Connection c, int code, String reason) {
    connections.remove(c.socket.getId(), c);
    try {
      c.socket.close(new CloseStatus(code, reason));
    } catch (IOException | RuntimeException ignored) {
    }
  }

  private static String text(JsonNode payload, String key) {
    JsonNode value = payload.get(key);
    require(value != null && value.isString(), 400, "INVALID_FRAME");
    return value.asText();
  }

  private static long number(JsonNode payload, String key) {
    JsonNode value = payload.get(key);
    require(
        value != null && value.isIntegralNumber() && value.canConvertToLong(),
        400,
        "INVALID_FRAME");
    return value.longValue();
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    connections.remove(session.getId());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable error) {
    var c = connections.get(session.getId());
    if (c != null) close(c, 1011, "TRANSPORT_ERROR");
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    String id = event.getSession().getId();
    for (var c : connections.values())
      if (c.http.getId().equals(id)) close(c, 4401, "SESSION_EXPIRED");
  }

  @PreDestroy
  public void shutdown() {
    timer.shutdownNow();
    for (var c : connections.values()) close(c, 1001, "SERVER_SHUTDOWN");
    senders.shutdownNow();
  }

  private record ReadKey(String room, String user) {}

  private static final class Connection {
    final WebSocketSession socket;
    final BintimePrincipal principal;
    final HttpSession http;
    final long opened;
    final Map<String, Long> subscriptions = new ConcurrentHashMap<>();
    final Set<String> dirtyRooms = ConcurrentHashMap.newKeySet();
    final Map<ReadKey, ChatService.ReadCommitted> pendingReads = new ConcurrentHashMap<>();
    final AtomicBoolean workPending = new AtomicBoolean();
    final AtomicBoolean workerRunning = new AtomicBoolean();
    final Map<String, Map<String, Long>> readSequences = new HashMap<>();
    boolean authenticated;
    volatile long lastInbound;
    long lastPing;
    int rateViolations;

    Connection(WebSocketSession socket, BintimePrincipal principal, HttpSession http, long now) {
      this.socket = socket;
      this.principal = principal;
      this.http = http;
      this.opened = now;
      this.lastInbound = now;
      this.lastPing = now;
    }
  }
}
