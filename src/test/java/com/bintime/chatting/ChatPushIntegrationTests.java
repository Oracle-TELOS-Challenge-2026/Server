package com.bintime.chatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bintime.chatting.dto.CreateChatRoomRequest;
import com.bintime.chatting.dto.SendChatMessageRequest;
import com.bintime.chatting.push.ChatPushNotifications;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.user.security.BintimePrincipal;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringJUnitConfig(ChatPushIntegrationTests.Application.class)
@ActiveProfiles("chat-push-integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ChatPushIntegrationTests {
  private static final BintimePrincipal CUSTOMER = new BintimePrincipal("customer", "CUSTOMER");
  private static final BintimePrincipal OWNER = new BintimePrincipal("owner", "OWNER");
  @Autowired JdbcTemplate db;
  @Autowired ChatService chats;
  @Autowired NotificationSubscriptions subscriptions;
  @Autowired FcmClient fcm;
  @Autowired TransactionTemplate transactions;
  @Autowired PushEvents committed;
  private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
  private final List<Attempt> accepted = new CopyOnWriteArrayList<>();
  private final AtomicBoolean providerFails = new AtomicBoolean();
  private String room;
  private MockHttpSession ownerSession;

  @BeforeEach
  void seed() {
    db.update("insert into user_accounts values('customer','고객','CUSTOMER')");
    db.update("insert into user_accounts values('owner','사장님','OWNER')");
    db.update("insert into store_shops values('shop','owner','빈틈 네일',null)");
    room = chats.create("customer", "create", new CreateChatRoomRequest("shop")).data().id();
    ownerSession = register(OWNER, "owner-fid");
    when(fcm.enabled()).thenReturn(true);
    when(fcm.sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              var attempt =
                  new Attempt(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      Map.copyOf(invocation.getArgument(3)),
                      invocation.getArgument(4),
                      TransactionSynchronizationManager.isActualTransactionActive());
              attempts.add(attempt);
              if (providerFails.get()) throw new IllegalStateException("provider unavailable");
              BooleanSupplier stillActive = invocation.getArgument(5);
              if (!stillActive.getAsBoolean())
                return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
              accepted.add(attempt);
              return new FcmClient.Result(true, "ACCEPTED", false, 0);
            });
  }

  @Test
  void restAndSocketWritesPushOnlyNewCommittedMessagesToTheOtherParticipantsFid() throws Exception {
    register(CUSTOMER, "customer-fid");
    // Several valid sessions bound to one browser installation must not duplicate the push.
    register(OWNER, "owner-fid");
    var firstRequest = new SendChatMessageRequest("customer-first", "예약 문의입니다");
    var first = chats.send("customer", room, "rest-request", firstRequest);
    assertThat(first.status()).isEqualTo(201);
    assertThat(chats.send("customer", room, firstRequest)).isEqualTo(first.data());
    assertThat(chats.send("customer", room, "another-http-key", firstRequest).status())
        .isEqualTo(200);
    assertThat(chats.send("customer", room, "rest-request", firstRequest)).isEqualTo(first);
    var replyRequest = new SendChatMessageRequest("owner-reply", "네 가능합니다");
    var reply = chats.send("owner", room, replyRequest);
    assertThat(chats.send("owner", room, "rest-retry", replyRequest).data()).isEqualTo(reply);
    await(() -> accepted.size() == 2);

    assertThat(committed.events).hasSize(2);
    assertThat(accepted)
        .allSatisfy(
            attempt -> {
              assertThat(attempt.transactionActive()).isFalse();
              assertThat(
                      db.queryForObject(
                          "select count(*) from chatting_messages where message_id=?",
                          Integer.class,
                          attempt.data().get("messageId")))
                  .isEqualTo(1);
              assertThat(attempt.data())
                  .containsEntry("type", "CHAT_MESSAGE")
                  .doesNotContainKeys("content", "contentText");
              assertThat(attempt.path()).startsWith("/chat?roomId=");
            });
    assertThat(accepted)
        .filteredOn(attempt -> first.data().id().equals(attempt.data().get("messageId")))
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.fid()).isEqualTo("owner-fid");
              assertThat(attempt.data()).containsEntry("roomId", room);
            });
    assertThat(accepted)
        .filteredOn(attempt -> reply.id().equals(attempt.data().get("messageId")))
        .singleElement()
        .satisfies(attempt -> assertThat(attempt.fid()).isEqualTo("customer-fid"));
    assertThat(count("chatting_messages")).isEqualTo(2);
  }

  @Test
  void rolledBackMessageNeverReachesThePushListener() {
    transactions.executeWithoutResult(
        status -> {
          chats.send("customer", room, new SendChatMessageRequest("rollback", "취소될 메시지"));
          status.setRollbackOnly();
        });

    assertThat(committed.events).isEmpty();
    assertThat(count("chatting_messages")).isZero();
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void providerFailureCannotUndoThePersistedMessageOrItsHttpSuccess() throws Exception {
    providerFails.set(true);
    var result =
        chats.send(
            "customer", room, "http-success", new SendChatMessageRequest("message", "저장됩니다"));
    await(() -> !attempts.isEmpty());

    assertThat(result.status()).isEqualTo(201);
    assertThat(count("chatting_messages")).isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select last_sequence from chatting_rooms where room_id=?", Long.class, room))
        .isEqualTo(1);
    assertThat(chats.history("owner", room, null, 30).items()).containsExactly(result.data());
    assertThat(accepted).isEmpty();
    assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.transactionActive()).isFalse());
  }

  @Test
  void messageAlreadyReadWhenTheTransactionCommitsIsNotPushed() {
    transactions.executeWithoutResult(
        status -> {
          chats.send("customer", room, new SendChatMessageRequest("already-read", "이미 읽었어요"));
          chats.read("owner", room, 1L);
        });

    assertThat(committed.events).hasSize(1);
    assertThat(
            db.queryForObject(
                "select last_read_sequence from chatting_read_positions where room_id=? and"
                    + " user_id='owner'",
                Long.class,
                room))
        .isEqualTo(1);
    verify(fcm, after(150).never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void invalidatedRecipientSessionIsPrunedBeforeSending() throws Exception {
    transactions.executeWithoutResult(
        status -> {
          chats.send("customer", room, new SendChatMessageRequest("logged-out", "로그아웃 후 메시지"));
          ownerSession.invalidate();
        });
    await(
        () ->
            "N"
                .equals(
                    db.queryForObject(
                        "select active_yn from notification_subscriptions where user_id='owner'",
                        String.class)));

    assertThat(committed.events).hasSize(1);
    assertThat(count("chatting_messages")).isEqualTo(1);
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void lastSubmissionGuardRejectsALogoutDuringProviderPreparation() throws Exception {
    AtomicBoolean submitted = new AtomicBoolean();
    AtomicBoolean checked = new AtomicBoolean();
    doAnswer(
            invocation -> {
              ownerSession.invalidate();
              BooleanSupplier stillActive = invocation.getArgument(5);
              submitted.set(stillActive.getAsBoolean());
              checked.set(true);
              return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
            })
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    chats.send("customer", room, new SendChatMessageRequest("guard", "제출 경계 검증"));
    await(checked::get);

    assertThat(submitted).isFalse();
    assertThat(count("chatting_messages")).isEqualTo(1);
  }

  private MockHttpSession register(BintimePrincipal principal, String fid) {
    var session = new MockHttpSession();
    session.setMaxInactiveInterval(0);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    subscriptions.register(principal, session, fid);
    return session;
  }

  private int count(String table) {
    return db.queryForObject("select count(*) from " + table, Integer.class);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
    assertThat(condition.getAsBoolean()).isTrue();
  }

  private record Attempt(
      String fid,
      String title,
      String body,
      Map<String, String> data,
      String path,
      boolean transactionActive) {}

  static class PushEvents {
    final List<ChatService.PushRequested> events = new CopyOnWriteArrayList<>();

    @TransactionalEventListener
    public void committed(ChatService.PushRequested event) {
      events.add(event);
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("chat-push-integration")
  @EnableTransactionManagement
  @Import({
    ChatService.class,
    ChatRepository.class,
    NotificationSubscriptions.class,
    ChatPushNotifications.class
  })
  static class Application {
    @Bean(destroyMethod = "close")
    HikariDataSource source() {
      var source = new HikariDataSource();
      source.setJdbcUrl(
          "jdbc:h2:mem:chat-push-"
              + UUID.randomUUID()
              + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
      source.setUsername("sa");
      source.setMaximumPoolSize(8);
      new ResourceDatabasePopulator(
              new ClassPathResource("chatting-schema.sql"),
              new ClassPathResource("notification-subscriptions-schema.sql"))
          .execute(source);
      return source;
    }

    @Bean
    JdbcTemplate jdbc(HikariDataSource source) {
      return new JdbcTemplate(source);
    }

    @Bean
    JdbcClient client(HikariDataSource source) {
      return JdbcClient.create(source);
    }

    @Bean
    DataSourceTransactionManager manager(HikariDataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    TransactionTemplate transactions(DataSourceTransactionManager manager) {
      return new TransactionTemplate(manager);
    }

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    JsonMapper json() {
      return JsonMapper.builder().build();
    }

    @Bean
    FcmClient fcm() {
      var fcm = mock(FcmClient.class);
      when(fcm.enabled()).thenReturn(true);
      return fcm;
    }

    @Bean
    PushEvents probe() {
      return new PushEvents();
    }
  }
}
