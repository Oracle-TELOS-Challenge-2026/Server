package com.bintime.notification.subscription;

import static org.assertj.core.api.Assertions.*;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.user.security.BintimePrincipal;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class NotificationSubscriptionsTests {
  private static final BintimePrincipal ALICE = new BintimePrincipal("alice", "CUSTOMER");
  private static final BintimePrincipal BOB = new BintimePrincipal("bob", "CUSTOMER");
  private HikariDataSource dataSource;
  private JdbcTemplate jdbc;
  private MutableClock clock;
  private NotificationSubscriptions subscriptions;

  @BeforeEach
  void setUp() {
    // H2의 CHECK 표현식은 생성한 물리 연결을 참조하므로 운영처럼 연결 풀을 유지한다.
    dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(
        "jdbc:h2:mem:subscriptions-"
            + UUID.randomUUID()
            + ";MODE=Oracle;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
    dataSource.setUsername("sa");
    dataSource.setPassword("");
    dataSource.setMaximumPoolSize(8);
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("CREATE TABLE user_accounts (user_id VARCHAR(64) PRIMARY KEY)");
    jdbc.update("INSERT INTO user_accounts VALUES ('alice'), ('bob')");
    new ResourceDatabasePopulator(new ClassPathResource("notification-subscriptions-schema.sql"))
        .execute(dataSource);
    clock = new MutableClock();
    subscriptions = service();
  }

  @AfterEach
  void closeDataSource() {
    dataSource.close();
  }

  private NotificationSubscriptions service() {
    return new NotificationSubscriptions(
        JdbcClient.create(dataSource),
        new DataSourceTransactionManager(dataSource),
        clock,
        "bintime-33512");
  }

  private MockHttpSession session(BintimePrincipal principal) {
    MockHttpSession session = new MockHttpSession();
    session.setMaxInactiveInterval(1800);
    authenticate(session, principal);
    return session;
  }

  private void authenticate(MockHttpSession session, BintimePrincipal principal) {
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
  }

  private void failure(Runnable call, int status, String code) {
    assertThatThrownBy(call::run)
        .isInstanceOfSatisfying(
            ApiBusinessException.class,
            exception -> {
              assertThat(exception.status).isEqualTo(status);
              assertThat(exception.code).isEqualTo(code);
            });
  }

  @Test
  void repeatedRegistrationPreservesIdentityAndCreationAndStoresNoBearerSessionId() {
    var session = session(ALICE);
    var first = subscriptions.register(ALICE, session, "fid-one");
    clock.advance(10);
    var repeated = subscriptions.register(ALICE, session, "fid-one");
    assertThat(first.created()).isTrue();
    assertThat(repeated.created()).isFalse();
    assertThat(repeated.subscription()).isEqualTo(first.subscription());
    assertThat(first.subscription().expiresAt()).isNull();
    assertThat(
            jdbc.queryForObject(
                "SELECT binding_id FROM notification_subscription_sessions", String.class))
        .isNotEqualTo(session.getId());
    assertThat(
            jdbc.queryForObject(
                "SELECT updated_at > created_at FROM notification_subscriptions", Boolean.class))
        .isTrue();
    assertThat(subscriptions.activeTargets("alice"))
        .containsExactly(
            new NotificationSubscriptions.Target(first.subscription().id(), "fid-one"));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"white space", "\tvalue", "value\n", "\u0001bad", "non\u00a0breaking"})
  void invalidFidDoesNotWrite(String fid) {
    failure(
        () -> subscriptions.register(ALICE, session(ALICE), fid), 400, "INVALID_INSTALLATION_ID");
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM notification_subscriptions", Integer.class))
        .isZero();
  }

  @Test
  void lengthBoundaryAndAuthenticationAreValidated() {
    subscriptions.register(ALICE, session(ALICE), "x".repeat(128));
    failure(
        () -> subscriptions.register(ALICE, session(ALICE), "x".repeat(129)),
        400,
        "INVALID_INSTALLATION_ID");
    failure(() -> subscriptions.register(null, session(ALICE), "fid"), 401, "UNAUTHENTICATED");
    failure(() -> subscriptions.register(ALICE, null, "fid"), 401, "UNAUTHENTICATED");
    failure(() -> subscriptions.register(ALICE, session(BOB), "fid"), 401, "UNAUTHENTICATED");
    failure(
        () -> subscriptions.register(ALICE, new MockHttpSession(), "fid"), 401, "UNAUTHENTICATED");
  }

  @Test
  void concurrentRegistrationCreatesOneResourceAndLinksEverySession() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<NotificationSubscriptions.Registration>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++)
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return subscriptions.register(ALICE, session(ALICE), "shared-fid");
                }));
      start.countDown();
      List<NotificationSubscriptions.Registration> registrations = new ArrayList<>();
      for (var result : results) registrations.add(result.get(10, TimeUnit.SECONDS));
      assertThat(registrations.stream().filter(NotificationSubscriptions.Registration::created))
          .hasSize(1);
      assertThat(registrations.stream().map(r -> r.subscription().id()).distinct()).hasSize(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM notification_subscription_sessions", Integer.class))
          .isEqualTo(8);
      assertThat(subscriptions.activeTargets("alice")).hasSize(1);
    }
  }

  @Test
  void concurrentDifferentAccountsNeverShareOneActiveFid() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Integer>> calls = new ArrayList<>();
      for (BintimePrincipal user : List.of(ALICE, BOB))
        calls.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    subscriptions.register(user, session(user), "contested-fid");
                    return 201;
                  } catch (ApiBusinessException conflict) {
                    assertThat(conflict.code).isEqualTo("SUBSCRIPTION_IN_USE");
                    return conflict.status;
                  }
                }));
      start.countDown();
      assertThat(
              List.of(
                  calls.get(0).get(10, TimeUnit.SECONDS), calls.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(201, 409);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM notification_subscriptions WHERE active_yn = 'Y'",
                  Integer.class))
          .isEqualTo(1);
    }
  }

  @Test
  void fidReplacementPreservesOtherSessionsAndConflictRollsBackPreviousBinding() {
    var firstSession = session(ALICE);
    var secondSession = session(ALICE);
    var first = subscriptions.register(ALICE, firstSession, "shared");
    subscriptions.register(ALICE, secondSession, "shared");
    subscriptions.register(ALICE, firstSession, "replacement");
    assertThat(subscriptions.activeTargets("alice"))
        .extracting(NotificationSubscriptions.Target::installationId)
        .containsExactlyInAnyOrder("shared", "replacement");
    subscriptions.register(BOB, session(BOB), "taken");
    failure(() -> subscriptions.register(ALICE, firstSession, "taken"), 409, "SUBSCRIPTION_IN_USE");
    assertThat(subscriptions.activeTargets("alice")).hasSize(2);
    subscriptions.releaseSession(secondSession);
    assertThat(subscriptions.activeTargets("alice"))
        .extracting(NotificationSubscriptions.Target::installationId)
        .containsExactly("replacement");
    assertThat(
            jdbc.queryForObject(
                "SELECT active_yn FROM notification_subscriptions WHERE subscription_id = ?",
                String.class,
                first.subscription().id()))
        .isEqualTo("N");
  }

  @Test
  void explicitDeleteReleasesAllSharingSessionsAndKeepsOtherFids() {
    var first = subscriptions.register(ALICE, session(ALICE), "shared");
    subscriptions.register(ALICE, session(ALICE), "shared");
    subscriptions.register(ALICE, session(ALICE), "other-browser");
    failure(() -> subscriptions.delete(BOB, first.subscription().id()), 404, "NOT_FOUND");
    assertThat(subscriptions.activeTargets("alice")).hasSize(2);
    subscriptions.delete(ALICE, first.subscription().id());
    subscriptions.delete(ALICE, first.subscription().id());
    subscriptions.delete(BOB, first.subscription().id());
    subscriptions.delete(ALICE, "unknown");
    assertThat(subscriptions.activeTargets("alice"))
        .extracting(NotificationSubscriptions.Target::installationId)
        .containsExactly("other-browser");
    AtomicBoolean called = new AtomicBoolean();
    assertThat(
            subscriptions
                .withActiveTarget("alice", first.subscription().id(), t -> called.getAndSet(true))
                .submitted())
        .isFalse();
    assertThat(called).isFalse();
  }

  @Test
  void logoutReleasesOnlyCurrentSessionAndLastSessionDisablesTarget() {
    var a = session(ALICE);
    var b = session(ALICE);
    subscriptions.register(ALICE, a, "shared");
    subscriptions.register(ALICE, b, "shared");
    subscriptions.releaseSession(a);
    a.invalidate();
    assertThat(subscriptions.hasActive("alice")).isTrue();
    subscriptions.releaseSession(b);
    assertThat(subscriptions.hasActive("alice")).isFalse();
    subscriptions.releaseSession(a);
  }

  @Test
  void invalidatedAndIdleSessionsAreExcludedBeforeSubmission() {
    var invalid = session(ALICE);
    var first = subscriptions.register(ALICE, invalid, "invalidated");
    invalid.invalidate();
    assertThat(
            subscriptions
                .withActiveTarget("alice", first.subscription().id(), target -> "must not run")
                .submitted())
        .isFalse();
    var idle = session(ALICE);
    idle.setMaxInactiveInterval(1);
    subscriptions.register(ALICE, idle, "idle");
    clock.advance(2);
    assertThat(subscriptions.hasActive("alice")).isFalse();
  }

  @Test
  void accountChangeCannotKeepPreviousUsersPushTargetAlive() {
    var session = session(ALICE);
    var original = subscriptions.register(ALICE, session, "browser");
    authenticate(session, BOB);
    assertThat(
            subscriptions
                .withActiveTarget("alice", original.subscription().id(), target -> "private")
                .submitted())
        .isFalse();
    var replacement = subscriptions.register(BOB, session, "browser");
    assertThat(replacement.created()).isTrue();
    assertThat(subscriptions.hasActive("alice")).isFalse();
    assertThat(subscriptions.hasActive("bob")).isTrue();
  }

  @Test
  void registrationAfterAccountChangePrunesPreviousIdentityBeforeCheckingFidOwnership() {
    var session = session(ALICE);
    subscriptions.register(ALICE, session, "browser");
    authenticate(session, BOB);
    subscriptions.register(BOB, session, "browser");
    assertThat(subscriptions.activeTargets("alice")).isEmpty();
    assertThat(subscriptions.activeTargets("bob")).hasSize(1);
  }

  @Test
  void restartRequiresFreshRegistrationButPreservesOriginalResourceIdentity() {
    var original = subscriptions.register(ALICE, session(ALICE), "browser");
    subscriptions = service();
    assertThat(subscriptions.hasActive("alice")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM notification_subscription_sessions", Integer.class))
        .isZero();
    var registered = subscriptions.register(ALICE, session(ALICE), "browser");
    assertThat(registered.created()).isFalse();
    assertThat(registered.subscription()).isEqualTo(original.subscription());
  }

  @Test
  void fcmUnregisteredDisablesEveryBindingAndCallbackHasNoDatabaseTransaction() {
    var first = subscriptions.register(ALICE, session(ALICE), "browser");
    subscriptions.register(ALICE, session(ALICE), "browser");
    var delivery =
        subscriptions.withActiveTarget(
            "alice",
            first.subscription().id(),
            target -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              subscriptions.invalidate(target.id());
              return "UNREGISTERED";
            });
    assertThat(delivery.submitted()).isTrue();
    assertThat(delivery.value()).isEqualTo("UNREGISTERED");
    assertThat(subscriptions.hasActive("alice")).isFalse();
  }

  @Test
  void deleteWaitsForInFlightSubmissionAndPreventsEveryLaterSubmission() throws Exception {
    var registered = subscriptions.register(ALICE, session(ALICE), "browser");
    String id = registered.subscription().id();
    try (var pool = Executors.newFixedThreadPool(2)) {
      CountDownLatch submitted = new CountDownLatch(1);
      CountDownLatch finish = new CountDownLatch(1);
      var send =
          pool.submit(
              () ->
                  subscriptions.withActiveTarget(
                      "alice",
                      id,
                      target -> {
                        submitted.countDown();
                        try {
                          assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException exception) {
                          throw new IllegalStateException(exception);
                        }
                        return "accepted";
                      }));
      assertThat(submitted.await(5, TimeUnit.SECONDS)).isTrue();
      var delete = pool.submit(() -> subscriptions.delete(ALICE, id));
      assertThat(delete.isDone()).isFalse();
      finish.countDown();
      assertThat(send.get(5, TimeUnit.SECONDS).submitted()).isTrue();
      delete.get(5, TimeUnit.SECONDS);
      assertThat(subscriptions.withActiveTarget("alice", id, target -> "unexpected").submitted())
          .isFalse();
    }
  }

  @Test
  void unregisteredSessionLogoutDoesNotRequireSubscriptionTables() {
    jdbc.execute("DROP TABLE notification_subscription_sessions");
    jdbc.execute("DROP TABLE notification_subscriptions");
    subscriptions.releaseSession(session(ALICE));
    subscriptions.releaseSession(null);
  }

  private static class MutableClock extends Clock {
    private Instant instant = Instant.now();

    void advance(long seconds) {
      instant = instant.plusSeconds(seconds);
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
      return instant;
    }
  }
}
