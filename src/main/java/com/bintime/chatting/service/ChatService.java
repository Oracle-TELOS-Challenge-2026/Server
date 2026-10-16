package com.bintime.chatting.service;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.common.dto.CursorPage;
import com.bintime.common.web.ApiBusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("!api-docs")
public class ChatService {
  public static final long MAX_SEQUENCE = 9007199254740991L;
  private final ChatRepository db;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json;
  private final ApplicationEventPublisher events;
  private final Map<String, Window> rates = new ConcurrentHashMap<>();

  public ChatService(
      ChatRepository db,
      TransactionTemplate tx,
      Clock clock,
      JsonMapper json,
      ApplicationEventPublisher events) {
    this.db = db;
    this.tx = tx;
    this.clock = clock;
    this.json = json;
    this.events = events;
  }

  public record Result<T>(T data, int status) {}

  public record MessageCommitted(ChatMessageResponse message) {}

  public record PushRequested(ChatMessageResponse message, String recipientId) {}

  public record ReadCommitted(String userId, ChatReadResponse read) {}

  private record Cursor(String user, String id, OffsetDateTime activity) {}

  private record Window(long second, int count) {}

  public Result<ChatRoomResponse> create(String user, String key, CreateChatRoomRequest request) {
    require(request != null, 400, "INVALID_REQUEST");
    id(request.storeId());
    header(key);
    return tx.execute(
        status -> {
          var actor = actor(user, true);
          require("CUSTOMER".equals(actor.role()), 403, "FORBIDDEN");
          String owner = db.storeOwner(request.storeId());
          require(owner != null, 404, "STORE_NOT_FOUND");
          require(!owner.equals(user), 403, "FORBIDDEN");
          return replay(
              user,
              "POST:/api/v1/chatting/rooms",
              key,
              request,
              ChatRoomResponse.class,
              () -> {
                String room = db.existingRoom(user, request.storeId());
                boolean created = room == null;
                if (created) {
                  room = "room_" + UUID.randomUUID();
                  db.createRoom(room, user, request.storeId(), now());
                }
                return new Result<>(db.view(user, room), created ? 201 : 200);
              });
        });
  }

  public ChatRoomListResponse list(String user, String cursor, int limit) {
    actor(user, false);
    require(limit >= 1 && limit <= 100, 400, "INVALID_LIMIT");
    Cursor before = null;
    if (cursor != null) {
      try {
        require(cursor.length() <= 1024, 400, "INVALID_CURSOR");
        before = json.readValue(Base64.getUrlDecoder().decode(cursor), Cursor.class);
        require(
            before != null && user.equals(before.user()) && before.activity() != null,
            400,
            "INVALID_CURSOR");
        id(before.id());
      } catch (RuntimeException failure) {
        throw new ApiBusinessException(400, "INVALID_CURSOR");
      }
    }
    var rows =
        db.list(
            user,
            before == null ? null : before.activity(),
            before == null ? null : before.id(),
            limit + 1);
    boolean more = rows.size() > limit;
    var visible = rows.subList(0, Math.min(rows.size(), limit));
    String next = null;
    if (more) {
      var last = visible.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  json.writeValueAsBytes(new Cursor(user, last.value().id(), last.activity())));
    }
    return new ChatRoomListResponse(
        visible.stream().map(ChatRepository.Listing::value).toList(), new CursorPage(next, more));
  }

  public ChatMessageListResponse history(String user, String id, Long before, int limit) {
    actor(user, false);
    var room = participant(user, id, false);
    require(limit >= 1 && limit <= 100, 400, "INVALID_LIMIT");
    if (before != null) sequence(before, false);
    var rows = db.messages(room, before, 0, MAX_SEQUENCE, limit + 1, false);
    boolean older = rows.size() > limit;
    var visible = new ArrayList<>(rows.subList(0, Math.min(rows.size(), limit)));
    Collections.reverse(visible);
    return new ChatMessageListResponse(
        visible,
        new ChatMessageListResponse.Page(older, older ? visible.getFirst().sequence() : null));
  }

  public Result<ChatMessageResponse> send(
      String user, String id, String key, SendChatMessageRequest request) {
    validate(request);
    header(key);
    id(id);
    return tx.execute(
        status -> {
          actor(user, true);
          var room = participant(user, id, true);
          return replay(
              user,
              "POST:/api/v1/chatting/rooms/" + id + "/messages",
              key,
              request,
              ChatMessageResponse.class,
              () -> write(user, room, request));
        });
  }

  public ChatMessageResponse send(String user, String id, SendChatMessageRequest request) {
    validate(request);
    id(id);
    return tx.execute(
        status -> {
          actor(user, true);
          return write(user, participant(user, id, true), request).data();
        });
  }

  private Result<ChatMessageResponse> write(
      String user, ChatRepository.Room room, SendChatMessageRequest request) {
    var existing = db.duplicate(room, user, request.clientMessageId());
    if (existing != null) {
      require(existing.content().equals(request.content()), 409, "CLIENT_MESSAGE_ID_REUSED");
      return new Result<>(existing, 200);
    }
    require("OPEN".equals(room.status()), 409, "ROOM_READ_ONLY");
    require(room.sequence() < MAX_SEQUENCE, 409, "SEQUENCE_EXHAUSTED");
    rate(user);
    var message =
        new ChatMessageResponse(
            "msg_" + UUID.randomUUID(),
            room.id(),
            room.sequence() + 1,
            new ChatMessageResponse.Sender(
                user, user.equals(room.customer()) ? "CUSTOMER" : "OWNER", db.displayName(user)),
            request.clientMessageId(),
            "TEXT",
            request.content(),
            now());
    db.insertMessage(message);
    // Subscribers only observe this event after the message and room counter commit together.
    events.publishEvent(new MessageCommitted(message));
    // Pin the other participant while the room is locked; later owner changes must not retarget it.
    events.publishEvent(
        new PushRequested(message, user.equals(room.customer()) ? room.owner() : room.customer()));
    return new Result<>(message, 201);
  }

  public ChatReadResponse read(String user, String id, Long sequence) {
    sequence(sequence, true);
    id(id);
    return tx.execute(
        status -> {
          actor(user, true);
          var room = participant(user, id, true);
          require(sequence <= room.sequence(), 400, "INVALID_READ_SEQUENCE");
          var old = db.read(id, user);
          if (sequence <= old.sequence()) return new ChatReadResponse(id, old.sequence(), old.at());
          var result = new ChatReadResponse(id, sequence, now());
          db.read(id, user, sequence, result.readAt());
          events.publishEvent(new ReadCommitted(user, result));
          return result;
        });
  }

  public long latest(String user, String id) {
    actor(user, false);
    return participant(user, id, false).sequence();
  }

  public List<ChatMessageResponse> replay(
      String user, String id, long after, long through, int limit) {
    actor(user, false);
    var room = participant(user, id, false);
    sequence(after, true);
    sequence(through, true);
    require(limit >= 1 && limit <= 1000, 400, "INVALID_LIMIT");
    return db.messages(room, null, after, through, limit, true);
  }

  private ChatRepository.User actor(String user, boolean lock) {
    require(user != null && !user.isBlank(), 401, "UNAUTHORIZED");
    // Take the user lock before any room lock: retries cannot race on a new HTTP key.
    var actor = db.user(user, lock);
    require(actor != null, 401, "UNAUTHORIZED");
    return actor;
  }

  private ChatRepository.Room participant(String user, String id, boolean lock) {
    id(id);
    var room = db.room(id);
    require(
        room != null && (user.equals(room.customer()) || user.equals(room.owner())),
        404,
        "ROOM_NOT_FOUND");
    if (lock) {
      require(db.lockRoom(id), 404, "ROOM_NOT_FOUND");
      room = db.room(id);
      require(
          room != null && (user.equals(room.customer()) || user.equals(room.owner())),
          404,
          "ROOM_NOT_FOUND");
    }
    return room;
  }

  private <T> Result<T> replay(
      String user,
      String operation,
      String key,
      Object request,
      Class<T> type,
      Supplier<Result<T>> action) {
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
    var saved = db.replay(user, operation, key);
    if (saved != null && saved.expires().isAfter(now())) {
      require(saved.fingerprint().equals(hash), 409, "IDEMPOTENCY_KEY_REUSED");
      return new Result<>(json.readValue(saved.json(), type), saved.status());
    }
    var result = action.get();
    db.saveReplay(
        user,
        operation,
        key,
        hash,
        json.writeValueAsString(result.data()),
        result.status(),
        now().plusHours(24));
    return result;
  }

  private void rate(String user) {
    long second = clock.millis() / 1000;
    rates.entrySet().removeIf(entry -> entry.getValue().second() < second - 60);
    rates.compute(
        user,
        (key, window) -> {
          int count = window == null || window.second() != second ? 0 : window.count();
          require(count < 5, 429, "RATE_LIMITED");
          return new Window(second, count + 1);
        });
  }

  private OffsetDateTime now() {
    // Match TIMESTAMP(6), so the first ACK and later DB/retry responses stay identical.
    return OffsetDateTime.now(clock).truncatedTo(ChronoUnit.MICROS);
  }

  private static void validate(SendChatMessageRequest request) {
    require(request != null, 400, "INVALID_REQUEST");
    id(request.clientMessageId());
    require(
        request.content() != null
            && !request.content().isBlank()
            && request.content().length() <= 2000,
        400,
        "INVALID_CONTENT");
  }

  private static void header(String key) {
    require(
        key != null
            && !key.isBlank()
            && key.getBytes(StandardCharsets.UTF_8).length <= 200
            && key.chars().noneMatch(Character::isISOControl),
        400,
        "INVALID_IDEMPOTENCY_KEY");
  }

  public static void id(String id) {
    require(
        id != null
            && !id.isBlank()
            && id.getBytes(StandardCharsets.UTF_8).length <= 64
            && id.chars().noneMatch(c -> Character.isISOControl(c) || Character.isWhitespace(c)),
        400,
        "INVALID_ID");
  }

  private static void sequence(Long sequence, boolean zero) {
    require(
        sequence != null && sequence >= (zero ? 0 : 1) && sequence <= MAX_SEQUENCE,
        400,
        "INVALID_SEQUENCE");
  }
}
