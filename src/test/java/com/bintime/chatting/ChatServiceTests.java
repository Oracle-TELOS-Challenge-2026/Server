package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;

import com.bintime.chatting.dto.*;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;

class ChatServiceTests {
  private JdbcTemplate db;
  private ChatRepository repository;
  private ChatService chats;
  private TransactionTemplate tx;
  private MutableClock clock;
  private HikariDataSource source;
  private final List<Object> committed = new CopyOnWriteArrayList<>();
  private final JsonMapper json = JsonMapper.builder().build();
  private final ApplicationEventPublisher publisher =
      event -> {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
        TransactionSynchronizationManager.registerSynchronization(
            new TransactionSynchronization() {
              @Override
              public void afterCommit() {
                committed.add(event);
              }
            });
      };

  @BeforeEach
  void setup() {
    source = new HikariDataSource();
    source.setJdbcUrl("jdbc:h2:mem:chat-" + UUID.randomUUID() + ";MODE=Oracle;LOCK_TIMEOUT=10000");
    source.setUsername("sa");
    source.setMaximumPoolSize(16);
    new ResourceDatabasePopulator(new ClassPathResource("chatting-schema.sql")).execute(source);
    db = new JdbcTemplate(source);
    repository = new ChatRepository(db);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    clock = new MutableClock(Instant.parse("2026-10-16T10:00:00.123456789Z"));
    chats =
        new ChatService(
            repository,
            tx,
            clock,
            json,
            publisher,
            org.mockito.Mockito.mock(com.bintime.store.contract.StoreReservationPort.class));
    committed.clear();
    for (String user : List.of("customer", "customer2", "outsider"))
      db.update("insert into user_accounts values(?,?, 'CUSTOMER')", user, user + " 이름");
    for (String user : List.of("owner", "owner2"))
      db.update("insert into user_accounts values(?,?, 'OWNER')", user, user + " 이름");
    db.update("insert into store_shops values('shop','owner','빈틈 네일',null)");
    db.update("insert into store_shops values('shop2','owner','빈틈 헤어',null)");
    db.update("insert into store_shops values('shop3','owner2','다른 매장',null)");
  }

  @AfterEach
  void close() {
    source.close();
  }

  @Test
  void roomCreationReplaysOriginalStatusAndSnapshotButNewKeyReturnsExistingRoom() {
    var request = new CreateChatRoomRequest("shop");
    var first = chats.create("customer", "create", request);
    assertThat(first.status()).isEqualTo(201);
    assertThat(first.data().unreadCount()).isZero();
    assertThat(first.data().lastReadSequence()).isZero();
    assertThat(first.data().peerReadSequence()).isZero();
    String legacy = json.writeValueAsString(first.data()).replace("\"peerReadSequence\":0,", "");
    assertThat(json.readValue(legacy, ChatRoomResponse.class).peerReadSequence()).isZero();
    assertThat(first.data().lastMessage()).isNull();
    chats.send("owner", first.data().id(), new SendChatMessageRequest("reply", "예약 가능합니다"));
    assertThat(chats.create("customer", "create", request)).isEqualTo(first);
    var existing = chats.create("customer", "another-key", request);
    assertThat(existing.status()).isEqualTo(200);
    assertThat(existing.data().id()).isEqualTo(first.data().id());
    assertThat(existing.data().unreadCount()).isEqualTo(1);
    assertThat(count("chatting_rooms")).isEqualTo(1);
    failure(
        409,
        "IDEMPOTENCY_KEY_REUSED",
        () -> chats.create("customer", "create", new CreateChatRoomRequest("shop2")));
    clock.advance(Duration.ofHours(24));
    assertThat(chats.create("customer", "create", new CreateChatRoomRequest("shop2")).status())
        .isEqualTo(201);
  }

  @Test
  void ownerCanChatAsCustomerWithAnotherStore() {
    var room = chats.create("owner", "customer-chat", new CreateChatRoomRequest("shop3"));
    assertThat(room.status()).isEqualTo(201);
    assertThat(room.data().customer().id()).isEqualTo("owner");
  }

  @Test
  void ownStoreChatIsRejectedAndReadRowsDoNotGrantMembership() {
    failure(
        403, "FORBIDDEN", () -> chats.create("owner", "key", new CreateChatRoomRequest("shop")));
    failure(
        404,
        "STORE_NOT_FOUND",
        () -> chats.create("customer", "key", new CreateChatRoomRequest("missing")));
    failure(401, "UNAUTHORIZED", () -> chats.list("missing", null, 20));
    String room = room();
    db.update("insert into chatting_read_positions values(?, 'outsider', 0, null)", room);
    failure(404, "ROOM_NOT_FOUND", () -> chats.history("outsider", room, null, 20));
    failure(404, "ROOM_NOT_FOUND", () -> chats.send("outsider", room, message("foreign")));
    failure(404, "ROOM_NOT_FOUND", () -> chats.read("outsider", room, 0L));
    failure(404, "ROOM_NOT_FOUND", () -> chats.readSnapshots("outsider", room));
    assertThat(chats.list("outsider", null, 20).items()).isEmpty();
    db.update("update store_shops set owner_user_id='owner2' where store_id='shop'");
    failure(404, "ROOM_NOT_FOUND", () -> chats.latest("owner", room));
    assertThat(chats.latest("owner2", room)).isZero();
  }

  @Test
  void restAndWebSocketSharePermanentDedupEvenAfterHttpKeyExpires() {
    String room = room();
    var request = message("client-id");
    var first = chats.send("customer", room, "http-key", request);
    assertThat(first.status()).isEqualTo(201);
    assertThat(chats.send("customer", room, request)).isEqualTo(first.data());
    assertThat(chats.send("customer", room, "http-key", request)).isEqualTo(first);
    assertThat(chats.send("customer", room, "new-key", request).status()).isEqualTo(200);
    failure(
        409,
        "CLIENT_MESSAGE_ID_REUSED",
        () -> chats.send("customer", room, new SendChatMessageRequest("client-id", "변경된 내용")));
    failure(
        409,
        "IDEMPOTENCY_KEY_REUSED",
        () -> chats.send("customer", room, "http-key", message("different-id")));
    clock.advance(Duration.ofHours(24));
    assertThat(chats.send("customer", room, "http-key", request).status()).isEqualTo(200);
    assertThat(count("chatting_messages")).isEqualTo(1);
    assertThat(committed).hasSize(2).first().isInstanceOf(ChatService.MessageCommitted.class);
    assertThat(committed.getLast()).isEqualTo(new ChatService.PushRequested(first.data(), "owner"));
    assertThat(chats.history("owner", room, null, 30).items()).containsExactly(first.data());
  }

  @Test
  void websocketFirstMessageCanBeReplayedByRestAndReadOnlyStillAcknowledgesDuplicates() {
    String room = room();
    var request = message("socket-first");
    var first = chats.send("owner", room, request);
    assertThat(first.sender().role()).isEqualTo("OWNER");
    db.update("update chatting_rooms set status_code='READ_ONLY' where room_id=?", room);
    assertThat(chats.send("owner", room, "rest", request).data()).isEqualTo(first);
    failure(409, "ROOM_READ_ONLY", () -> chats.send("owner", room, message("new")));
    assertThat(chats.read("customer", room, 1L).lastReadSequence()).isEqualTo(1);
    assertThat(chats.history("customer", room, null, 20).items()).hasSize(1);
    assertThat(count("chatting_messages")).isEqualTo(1);
  }

  @Test
  void readPositionIsMonotonicAndUnreadCountsOnlyOtherSenderMessages() {
    String room = room();
    assertThat(chats.read("customer", room, 0L).readAt()).isNull();
    chats.send("customer", room, message("one"));
    chats.send("owner", room, message("two"));
    chats.send("customer", room, message("three"));
    chats.send("owner", room, message("four"));
    assertThat(chats.list("customer", null, 20).items().getFirst().unreadCount()).isEqualTo(2);
    assertThat(chats.list("owner", null, 20).items().getFirst().unreadCount()).isEqualTo(2);
    var read = chats.read("customer", room, 2L);
    clock.advance(Duration.ofSeconds(2));
    assertThat(chats.read("customer", room, 1L)).isEqualTo(read);
    assertThat(chats.read("customer", room, 2L)).isEqualTo(read);
    assertThat(chats.list("customer", null, 20).items().getFirst().unreadCount()).isEqualTo(1);
    failure(400, "INVALID_READ_SEQUENCE", () -> chats.read("customer", room, 5L));
    failure(400, "INVALID_SEQUENCE", () -> chats.read("customer", room, -1L));
    assertThat(committed.stream().filter(ChatService.ReadCommitted.class::isInstance)).hasSize(1);
  }

  @Test
  void roomViewsRestoreTheOtherParticipantsReadPosition() {
    String room = room();
    assertThat(repository.view("customer", room).peerReadSequence()).isZero();
    assertThat(chats.readSnapshots("customer", room))
        .containsExactly(
            new ChatService.ReadCommitted("customer", new ChatReadResponse(room, 0L, null)),
            new ChatService.ReadCommitted("owner", new ChatReadResponse(room, 0L, null)));
    chats.send("customer", room, message("one"));
    chats.send("owner", room, message("two"));
    chats.read("customer", room, 1L);
    chats.read("owner", room, 2L);
    // A stray read row must not be mistaken for the other room participant.
    db.update("insert into chatting_read_positions values(?, 'outsider', 0, null)", room);

    var reloaded =
        new ChatService(
            repository,
            tx,
            clock,
            json,
            publisher,
            org.mockito.Mockito.mock(com.bintime.store.contract.StoreReservationPort.class));
    var customer = reloaded.list("customer", null, 20).items().getFirst();
    var owner = reloaded.list("owner", null, 20).items().getFirst();
    assertThat(customer.lastReadSequence()).isEqualTo(1);
    assertThat(customer.peerReadSequence()).isEqualTo(2);
    assertThat(owner.lastReadSequence()).isEqualTo(2);
    assertThat(owner.peerReadSequence()).isEqualTo(1);
    assertThat(reloaded.readSnapshots("customer", room))
        .extracting(ChatService.ReadCommitted::userId)
        .containsExactly("customer", "owner");
    assertThat(reloaded.list("outsider", null, 20).items()).isEmpty();
  }

  @Test
  void historyAndReplayUseStableSequenceBoundaries() {
    String room = room();
    for (int i = 1; i <= 5; i++) chats.send("customer", room, message("message-" + i));
    var newest = chats.history("owner", room, null, 2);
    assertThat(newest.items()).extracting(ChatMessageResponse::sequence).containsExactly(4L, 5L);
    assertThat(newest.page().hasOlder()).isTrue();
    assertThat(newest.page().nextBeforeSequence()).isEqualTo(4);
    var older = chats.history("owner", room, newest.page().nextBeforeSequence(), 3);
    assertThat(older.items()).extracting(ChatMessageResponse::sequence).containsExactly(1L, 2L, 3L);
    assertThat(older.page().hasOlder()).isFalse();
    assertThat(older.page().nextBeforeSequence()).isNull();
    assertThat(chats.replay("owner", room, 1, 4, 1000))
        .extracting(ChatMessageResponse::sequence)
        .containsExactly(2L, 3L, 4L);
    assertThat(chats.history("owner", room, 1L, 20).items()).isEmpty();
  }

  @Test
  void roomCursorIsUserBoundAndOrdersActivityWithIdAsTieBreaker() {
    String first = room();
    String second = chats.create("customer", "two", new CreateChatRoomRequest("shop2")).data().id();
    String third =
        chats.create("customer", "three", new CreateChatRoomRequest("shop3")).data().id();
    var ids = new ArrayList<>(List.of(first, second, third));
    ids.sort(Comparator.reverseOrder());
    var page = chats.list("customer", null, 2);
    assertThat(page.items())
        .extracting(ChatRoomResponse::id)
        .containsExactlyElementsOf(ids.subList(0, 2));
    assertThat(page.page().hasNext()).isTrue();
    assertThat(chats.list("customer", page.page().nextCursor(), 2).items())
        .extracting(ChatRoomResponse::id)
        .containsExactly(ids.getLast());
    failure(400, "INVALID_CURSOR", () -> chats.list("owner", page.page().nextCursor(), 2));
    failure(400, "INVALID_CURSOR", () -> chats.list("customer", "not-json", 2));
    clock.advance(Duration.ofSeconds(1));
    chats.send("owner", first, message("latest"));
    assertThat(chats.list("customer", null, 1).items().getFirst().id()).isEqualTo(first);
    assertThat(chats.list("owner", null, 20).items()).hasSize(2);
  }

  @Test
  void concurrentCreationAndDuplicateSendsProduceOnlyOnePersistentResult() throws Exception {
    var created =
        concurrent(
            12, i -> chats.create("customer", "same-create", new CreateChatRoomRequest("shop")));
    assertThat(created).allMatch(result -> result.status() == 201);
    assertThat(created.stream().map(result -> result.data().id()).distinct()).hasSize(1);
    String room = created.getFirst().data().id();
    var messages = concurrent(12, i -> chats.send("customer", room, message("retry")));
    assertThat(messages).allMatch(result -> result.sequence() == 1);
    assertThat(messages.stream().map(ChatMessageResponse::id).distinct()).hasSize(1);
    assertThat(count("chatting_rooms")).isEqualTo(1);
    assertThat(count("chatting_messages")).isEqualTo(1);
    assertThat(committed).hasSize(2);
  }

  @Test
  void customerAndOwnerConcurrentSendsAllocateContiguousSequences() throws Exception {
    String room = room();
    var messages =
        concurrent(
            10, i -> chats.send(i % 2 == 0 ? "customer" : "owner", room, message("message-" + i)));
    assertThat(messages)
        .extracting(ChatMessageResponse::sequence)
        .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    assertThat(chats.latest("owner", room)).isEqualTo(10);
    assertThat(chats.history("customer", room, null, 100).items()).hasSize(10);
    assertThat(committed).hasSize(20);
  }

  @Test
  void replayFailureRollsBackMessageCounterAndPostCommitEvent() {
    String room = room();
    var failingRepository =
        new ChatRepository(db) {
          @Override
          public void saveReplay(
              String user,
              String operation,
              String key,
              String hash,
              String json,
              int status,
              OffsetDateTime expires) {
            throw new IllegalStateException("cannot save replay");
          }
        };
    var failingService =
        new ChatService(
            failingRepository,
            tx,
            clock,
            json,
            publisher,
            org.mockito.Mockito.mock(com.bintime.store.contract.StoreReservationPort.class));
    assertThatThrownBy(() -> failingService.send("customer", room, "key", message("rollback")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(chats.latest("owner", room)).isZero();
    assertThat(count("chatting_messages")).isZero();
    assertThat(committed).isEmpty();
    assertThat(chats.send("customer", room, message("rollback")).sequence()).isEqualTo(1);
  }

  @Test
  void rateLimitIsPerUserAcrossRoomsAndTransportsWithoutBlockingDuplicateAck() {
    String first = room();
    String second = chats.create("customer", "two", new CreateChatRoomRequest("shop2")).data().id();
    for (int i = 0; i < 5; i++) {
      String target = i % 2 == 0 ? first : second;
      if (i % 2 == 0) chats.send("customer", target, "http-" + i, message("message-" + i));
      else chats.send("customer", target, message("message-" + i));
    }
    failure(429, "RATE_LIMITED", () -> chats.send("customer", first, message("sixth")));
    assertThat(chats.send("customer", first, message("message-0")).sequence()).isEqualTo(1);
    assertThat(chats.send("owner", first, message("owner"))).isNotNull();
    clock.advance(Duration.ofSeconds(1));
    assertThat(chats.send("customer", first, message("sixth"))).isNotNull();
  }

  @Test
  void trustBoundaryRejectsOversizeIdsContentHeadersAndUnsafeSequences() {
    String room = room();
    failure(400, "INVALID_ID", () -> chats.send("customer", room, message("한".repeat(22))));
    failure(
        400,
        "INVALID_CONTENT",
        () -> chats.send("customer", room, new SendChatMessageRequest("id", " ")));
    failure(
        400,
        "INVALID_CONTENT",
        () -> chats.send("customer", room, new SendChatMessageRequest("id", "a".repeat(2001))));
    failure(
        400, "INVALID_IDEMPOTENCY_KEY", () -> chats.send("customer", room, "a\nb", message("id")));
    failure(
        400,
        "INVALID_IDEMPOTENCY_KEY",
        () -> chats.create("customer", "한".repeat(67), new CreateChatRoomRequest("shop")));
    failure(
        400, "INVALID_SEQUENCE", () -> chats.read("customer", room, ChatService.MAX_SEQUENCE + 1));
    failure(400, "INVALID_LIMIT", () -> chats.history("customer", room, null, 101));
    failure(400, "INVALID_LIMIT", () -> chats.list("customer", null, 0));
    assertThat(count("chatting_messages")).isZero();
  }

  @Test
  void pushEligibilityUsesPersistedMessageCurrentParticipantsAndRecipientReadPosition() {
    String room = room();
    var customerMessage = chats.send("customer", room, message("for-owner"));
    assertThat(repository.pushEligible(customerMessage, "owner")).isTrue();
    assertThat(repository.pushEligible(customerMessage, "customer")).isFalse();
    assertThat(repository.pushEligible(customerMessage, "outsider")).isFalse();
    chats.read("owner", room, 1L);
    assertThat(repository.pushEligible(customerMessage, "owner")).isFalse();
    var ownerMessage = chats.send("owner", room, message("for-customer"));
    assertThat(repository.pushEligible(ownerMessage, "customer")).isTrue();
    db.update("update store_shops set owner_user_id='owner2' where store_id='shop'");
    assertThat(repository.pushEligible(customerMessage, "owner")).isFalse();
    assertThat(repository.pushEligible(ownerMessage, "customer")).isFalse();
  }

  private String room() {
    return chats.create("customer", "room", new CreateChatRoomRequest("shop")).data().id();
  }

  private SendChatMessageRequest message(String id) {
    return new SendChatMessageRequest(id, "예약 문의 " + id);
  }

  private long count(String table) {
    return db.queryForObject("select count(*) from " + table, Long.class);
  }

  private static void failure(int status, String code, Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfSatisfying(
            ApiBusinessException.class,
            failure -> {
              assertThat(failure.status).isEqualTo(status);
              assertThat(failure.code).isEqualTo(code);
            });
  }

  private static <T> List<T> concurrent(int count, IntFunction<T> action) throws Exception {
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new CountDownLatch(1);
      var futures = new ArrayList<Future<T>>();
      for (int i = 0; i < count; i++) {
        int index = i;
        futures.add(
            executor.submit(
                () -> {
                  start.await();
                  return action.apply(index);
                }));
      }
      start.countDown();
      var results = new ArrayList<T>();
      for (var future : futures) results.add(future.get(20, TimeUnit.SECONDS));
      return results;
    }
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;

    MutableClock(Instant instant) {
      now = new AtomicReference<>(instant);
    }

    void advance(Duration duration) {
      now.updateAndGet(instant -> instant.plus(duration));
    }

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
      return now.get();
    }
  }
}
