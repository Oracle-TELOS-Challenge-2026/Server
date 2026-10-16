package com.bintime.notification;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.service.NotificationDeliveryWorker;
import com.bintime.notification.service.NotificationService;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.dto.WaitlistResponse;
import com.bintime.search.service.ConditionRules;
import com.bintime.search.service.SearchFailure;
import com.bintime.search.service.WaitlistService;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.user.security.BintimePrincipal;
import java.time.*;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class NotificationDeliveryTests {
  static final BintimePrincipal USER = new BintimePrincipal("user", "CUSTOMER");
  JdbcTemplate jdbc;
  TransactionTemplate tx;
  MutableClock clock;
  NotificationRepository db;
  NotificationService service;
  NotificationDeliveryWorker worker;
  NotificationSubscriptions subscriptions;
  WaitlistService waitlists;
  SearchStoreCatalog catalog;
  FcmClient fcm;
  AtomicBoolean active;

  @BeforeEach
  void setup() {
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("notification-delivery-schema.sql"))
        .execute(source);
    jdbc = new JdbcTemplate(source);
    jdbc.update("insert into user_accounts(user_id) values ('user'),('other')");
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    clock = new MutableClock();
    db = new NotificationRepository(jdbc, JsonMapper.builder().build());
    subscriptions = mock(NotificationSubscriptions.class);
    waitlists = mock(WaitlistService.class);
    catalog = mock(SearchStoreCatalog.class);
    fcm = mock(FcmClient.class);
    active = new AtomicBoolean(true);
    when(subscriptions.activeTargets("user"))
        .thenReturn(List.of(new NotificationSubscriptions.Target("push-1", "fid-1")));
    when(subscriptions.withActiveTarget(eq("user"), anyString(), any()))
        .thenAnswer(
            invocation -> {
              if (!active.get()) return new NotificationSubscriptions.Delivery<>(false, null);
              Function<NotificationSubscriptions.Target, Object> callback =
                  invocation.getArgument(2);
              String id = invocation.getArgument(1);
              return new NotificationSubscriptions.Delivery<>(
                  true, callback.apply(new NotificationSubscriptions.Target(id, "fid-" + id)));
            });
    when(waitlists.get(eq("user"), anyString())).thenAnswer(invocation -> waitlist("ACTIVE", 1));
    when(catalog.revalidate(any()))
        .thenAnswer(
            invocation -> {
              Collection<String> ids = invocation.getArgument(0);
              return ids.stream().map(id -> slot(id, 1L)).toList();
            });
    when(fcm.enabled()).thenReturn(true);
    when(fcm.send(anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              BooleanSupplier guard = invocation.getArgument(2);
              return guard.getAsBoolean()
                  ? new FcmClient.Result(true, null, false, 0)
                  : new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
            });
    var similarity = mock(com.bintime.search.service.NotificationSimilarityService.class);
    when(similarity.matches(anyString(), any())).thenReturn(true);
    service =
        new NotificationService(
            db, subscriptions, waitlists, catalog, new ConditionRules(), tx, clock, similarity);
    worker = new NotificationDeliveryWorker(db, service, subscriptions, fcm, tx, clock, 0, false);
  }

  @AfterEach
  void teardown() {
    worker.shutdown();
  }

  @Test
  void concurrentSearchRetriesCreateOneAlertAndOneDeliveryPerBrowser() throws Exception {
    when(subscriptions.activeTargets("user"))
        .thenReturn(
            List.of(
                new NotificationSubscriptions.Target("push-1", "fid-1"),
                new NotificationSubscriptions.Target("push-2", "fid-2")));
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var calls =
          IntStream.range(0, 8)
              .mapToObj(
                  i -> (Callable<String>) () -> service.enqueue(request("slot")).notificationId())
              .toList();
      var ids =
          executor.invokeAll(calls).stream()
              .map(
                  future -> {
                    try {
                      return future.get();
                    } catch (Exception failure) {
                      throw new AssertionError(failure);
                    }
                  })
              .toList();
      assertThat(ids).doesNotContainNull().containsOnly(ids.getFirst());
    }
    assertThat(count("notification_alerts")).isEqualTo(1);
    assertThat(count("notification_deliveries")).isEqualTo(2);
    assertThat(count("notification_events")).isEqualTo(1);
  }

  @Test
  void retryKeepsAlertIdentityAndHonorsProviderDelayUntilAccepted() {
    String id = service.enqueue(request("slot")).notificationId();
    doReturn(
            new FcmClient.Result(false, "UNAVAILABLE", true, 180),
            new FcmClient.Result(true, null, false, 0))
        .when(fcm)
        .send(anyString(), any(), any());
    worker.run();
    assertThat(status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select available_at from notification_deliveries", Long.class))
        .isEqualTo(clock.millis() + 180_000);
    clock.advance(Duration.ofSeconds(179));
    worker.run();
    verify(fcm, times(1)).send(anyString(), any(), any());
    clock.advance(Duration.ofSeconds(1));
    worker.run();
    assertThat(status()).isEqualTo("ACCEPTED");
    assertThat(count("notification_alerts")).isEqualTo(1);
    assertThat(service.enqueue(request("slot")).notificationId()).isEqualTo(id);
    assertThat(jdbc.queryForObject("select push_status from notification_alerts", String.class))
        .isEqualTo("SENT");
    assertThat(jdbc.queryForObject("select attempts from notification_deliveries", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void disabledProviderPreservesQueueAndDoesNotConsumeAttempts() {
    service.enqueue(request("slot"));
    when(fcm.enabled()).thenReturn(false);
    worker.run();
    assertThat(status()).isEqualTo("QUEUED");
    assertThat(jdbc.queryForObject("select attempts from notification_deliveries", Integer.class))
        .isZero();
    verify(fcm, never()).send(anyString(), any(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"UNREGISTERED", "INVALID_ARGUMENT"})
  void onlyExplicitUnregisteredDeactivatesTheSubscription(String code) {
    service.enqueue(request("slot"));
    doReturn(new FcmClient.Result(false, code, false, 0)).when(fcm).send(anyString(), any(), any());
    worker.run();
    assertThat(status()).isEqualTo("FAILED");
    if (code.equals("UNREGISTERED")) verify(subscriptions).invalidate("push-1");
    else verify(subscriptions, never()).invalidate(anyString());
  }

  @ParameterizedTest
  @ValueSource(strings = {"CANCELED", "CHANGED"})
  void changedOrCanceledWaitlistCannotReachFcm(String state) {
    service.enqueue(request("slot"));
    when(waitlists.get("user", "wait"))
        .thenReturn(
            waitlist(
                state.equals("CANCELED") ? "CANCELED" : "ACTIVE", state.equals("CHANGED") ? 2 : 1));
    worker.run();
    assertThat(status()).isEqualTo("SKIPPED");
    verify(fcm, never()).send(anyString(), any(), any());
  }

  @Test
  void sessionRevokedDuringEligibilityValidationIsCheckedAgainBeforeSubmission() {
    service.enqueue(request("slot"));
    when(waitlists.get("user", "wait"))
        .thenAnswer(
            invocation -> {
              active.set(false);
              return waitlist("ACTIVE", 1);
            });
    worker.run();
    assertThat(status()).isEqualTo("SKIPPED");
    verify(fcm, never()).send(anyString(), any(), any());
  }

  @Test
  void slotRepublishingInvalidatesAnAlreadyQueuedCandidate() {
    service.enqueue(request("slot"));
    doReturn(List.of(slot("slot", 2L))).when(catalog).revalidate(any());
    worker.run();
    assertThat(status()).isEqualTo("SKIPPED");
    verify(fcm, never()).send(anyString(), any(), any());
  }

  @Test
  void userPacingDefersOtherDevicesWithoutConsumingTheirRetryBudget() {
    worker.shutdown();
    worker = new NotificationDeliveryWorker(db, service, subscriptions, fcm, tx, clock, 1, false);
    when(subscriptions.activeTargets("user"))
        .thenReturn(
            List.of(
                new NotificationSubscriptions.Target("push-1", "fid-1"),
                new NotificationSubscriptions.Target("push-2", "fid-2")));
    service.enqueue(request("slot"));
    worker.run();
    assertThat(jdbc.queryForList("select status from notification_deliveries", String.class))
        .containsExactlyInAnyOrder("ACCEPTED", "QUEUED");
    assertThat(
            jdbc.queryForObject(
                "select attempts from notification_deliveries where status='QUEUED'",
                Integer.class))
        .isZero();
    clock.advance(Duration.ofSeconds(1));
    worker.run();
    assertThat(jdbc.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("ACCEPTED");
  }

  @Test
  void listAndReadAreScopedIdempotentAndReflectCurrentAvailability() {
    String first = service.enqueue(request("slot-1")).notificationId();
    clock.advance(Duration.ofSeconds(1));
    String second = service.enqueue(request("slot-2")).notificationId();
    var page = service.list(USER, false, null, 1);
    assertThat(page.items()).extracting(n -> n.id()).containsExactly(second);
    assertThat(page.page().hasNext()).isTrue();
    assertThat(service.list(USER, false, page.page().nextCursor(), 1).items())
        .extracting(n -> n.id())
        .containsExactly(first);
    var read = service.read(USER, first, true);
    clock.advance(Duration.ofMinutes(1));
    assertThat(service.read(USER, first, true).readAt()).isEqualTo(read.readAt());
    assertThat(service.list(USER, true, null, 10).unreadCount()).isEqualTo(1);
    assertThat(count("notification_events")).isEqualTo(3);
    assertThatThrownBy(() -> service.read(new BintimePrincipal("other", "CUSTOMER"), first, true))
        .isInstanceOfSatisfying(
            ApiBusinessException.class, failure -> assertThat(failure.status).isEqualTo(404));
    doReturn(List.of()).when(catalog).revalidate(any());
    assertThat(service.list(USER, false, null, 10).items())
        .extracting(n -> n.availability())
        .containsOnly("UNAVAILABLE");
    doThrow(SearchFailure.unavailable()).when(catalog).revalidate(any());
    assertThat(service.list(USER, false, null, 10).items())
        .extracting(n -> n.availability())
        .containsOnly("UNKNOWN");
  }

  @Test
  void noActiveBrowserSkipsStorageAndInvalidInputCannotCreateAlerts() {
    when(subscriptions.activeTargets("user")).thenReturn(List.of());
    assertThat(service.enqueue(request("slot")).status()).isEqualTo("SKIPPED");
    assertThat(count("notification_alerts")).isZero();
    assertThatThrownBy(
            () ->
                service.enqueue(
                    new DispatchNotificationRequest(
                        "event", "user", "VACANCY_MATCHED", "slot", 1L, List.of(), null)))
        .isInstanceOf(ApiBusinessException.class);
  }

  @Test
  void concurrentDeviceCompletionsLeaveAConsistentTerminalAggregate() throws Exception {
    when(subscriptions.activeTargets("user"))
        .thenReturn(
            List.of(
                new NotificationSubscriptions.Target("push-1", "fid-1"),
                new NotificationSubscriptions.Target("push-2", "fid-2")));
    service.enqueue(request("slot"));
    var arrivals = new CountDownLatch(2);
    doAnswer(
            invocation -> {
              arrivals.countDown();
              assertThat(arrivals.await(5, TimeUnit.SECONDS)).isTrue();
              return new FcmClient.Result(true, null, false, 0);
            })
        .when(fcm)
        .send(anyString(), any(), any());
    var otherWorker =
        new NotificationDeliveryWorker(db, service, subscriptions, fcm, tx, clock, 0, false);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(worker::run);
      var second = executor.submit(otherWorker::run);
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      otherWorker.shutdown();
    }
    assertThat(jdbc.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("ACCEPTED");
    assertThat(jdbc.queryForObject("select push_status from notification_alerts", String.class))
        .isEqualTo("SENT");
  }

  @Test
  void expiredLeaseIsRecoveredAndOldWorkerCannotOverwriteAcceptance() {
    service.enqueue(request("slot"));
    var abandoned = db.claim(db.due(clock.millis()).getFirst(), clock.millis());
    clock.advance(Duration.ofSeconds(181));
    worker.run();
    assertThat(status()).isEqualTo("ACCEPTED");
    tx.executeWithoutResult(
        transaction -> {
          db.lockUser("user");
          db.finish(abandoned, "FAILED", "LATE_FAILURE", 0, true, OffsetDateTime.now(clock));
        });
    assertThat(status()).isEqualTo("ACCEPTED");
    assertThat(jdbc.queryForObject("select push_status from notification_alerts", String.class))
        .isEqualTo("SENT");
  }

  @Test
  void cancellationDuringCredentialRefreshFailsFinalSubmissionGuard() {
    service.enqueue(request("slot"));
    doAnswer(
            invocation -> {
              when(waitlists.get("user", "wait")).thenReturn(waitlist("CANCELED", 2));
              BooleanSupplier guard = invocation.getArgument(2);
              assertThat(guard.getAsBoolean()).isFalse();
              return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
            })
        .when(fcm)
        .send(anyString(), any(), any());
    worker.run();
    assertThat(status()).isEqualTo("SKIPPED");
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private String status() {
    return jdbc.queryForObject("select status from notification_deliveries", String.class);
  }

  private DispatchNotificationRequest request(String slot) {
    return new DispatchNotificationRequest(
        "match_" + "a".repeat(64),
        "user",
        "VACANCY_MATCHED",
        slot,
        1L,
        List.of(new DispatchNotificationRequest.MatcheItem("wait", 1L)),
        null);
  }

  private SearchStoreCatalog.Slot slot(String id, Long publication) {
    return new SearchStoreCatalog.Slot(
        id,
        "store",
        "매장",
        "NAIL",
        "service",
        "시술",
        37.5,
        127,
        OffsetDateTime.now(clock).plusHours(2),
        OffsetDateTime.now(clock).plusHours(3),
        50000,
        35000,
        "CANCELLATION",
        2,
        publication,
        null,
        "");
  }

  private WaitlistResponse waitlist(String status, long version) {
    var conditions =
        new WaitlistConditions(
            "store",
            "NAIL",
            null,
            OffsetDateTime.now(clock).plusHours(1),
            OffsetDateTime.now(clock).plusHours(4),
            null,
            null,
            60000L,
            List.of());
    return new WaitlistResponse(
        "wait",
        status,
        "MANUAL",
        conditions,
        null,
        null,
        "WAITING",
        List.of(),
        null,
        OffsetDateTime.now(clock),
        version);
  }

  static final class MutableClock extends Clock {
    Instant now = Instant.now();

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
