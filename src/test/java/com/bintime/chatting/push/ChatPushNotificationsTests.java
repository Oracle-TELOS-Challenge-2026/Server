package com.bintime.chatting.push;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.subscription.NotificationSubscriptions;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatPushNotificationsTests {
  private final ChatRepository chats = mock(ChatRepository.class);
  private final NotificationSubscriptions subscriptions = mock(NotificationSubscriptions.class);
  private final FcmClient fcm = mock(FcmClient.class);
  private final NotificationRepository history = mock(NotificationRepository.class);
  private final MutableClock clock = new MutableClock();
  private final ManualScheduler scheduler = new ManualScheduler(clock);
  private final AtomicBoolean eligible = new AtomicBoolean(true);
  private final Set<String> active = new HashSet<>();
  private ChatPushNotifications push;
  private static final FcmClient.Result ACCEPTED = new FcmClient.Result(true, "ACCEPTED", false, 0);

  @BeforeEach
  void setup() {
    push = new ChatPushNotifications(chats, subscriptions, fcm, clock, scheduler, 512, history);
    active.add("device-1");
    when(fcm.enabled()).thenReturn(true);
    when(chats.pushEligible(any(), anyString())).thenAnswer(ignored -> eligible.get());
    when(subscriptions.activeTargets("owner")).thenReturn(List.of(target("device-1")));
    doAnswer(
            invocation -> {
              String id = invocation.getArgument(1);
              if (!active.contains(id))
                return new NotificationSubscriptions.Delivery<>(false, null);
              Function<NotificationSubscriptions.Target, Object> submit = invocation.getArgument(2);
              return new NotificationSubscriptions.Delivery<>(true, submit.apply(target(id)));
            })
        .when(subscriptions)
        .withActiveTarget(eq("owner"), anyString(), any());
    when(fcm.sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any()))
        .thenAnswer(
            invocation ->
                ((BooleanSupplier) invocation.getArgument(5)).getAsBoolean()
                    ? ACCEPTED
                    : new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0));
  }

  @AfterEach
  void stop() {
    push.shutdown();
  }

  @Test
  void commitCallbackOnlyQueuesAndOneDeviceGetsSafeTypedChatPayloadOnce() {
    var event = event("msg-1", "room/한글", "😀".repeat(130));
    push.messageCommitted(event);
    push.messageCommitted(event);
    verifyNoInteractions(chats, subscriptions);
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    assertThat(scheduler.size()).isEqualTo(1);
    scheduler.runReady();
    var title = ArgumentCaptor.forClass(String.class);
    var body = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> data = ArgumentCaptor.forClass(Map.class);
    verify(fcm)
        .sendPush(
            eq("fid-device-1"),
            title.capture(),
            body.capture(),
            data.capture(),
            eq("/chat?roomId=room%2F%ED%95%9C%EA%B8%80"),
            any());
    assertThat(title.getValue()).endsWith("님의 새 메시지");
    assertThat(body.getValue().codePointCount(0, body.getValue().length())).isEqualTo(120);
    assertThat(body.getValue()).endsWith("…");
    assertThat(data.getValue())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "type",
                "CHAT_MESSAGE",
                "roomId",
                "room/한글",
                "messageId",
                "msg-1",
                "notificationId",
                "msg-1",
                "sequence",
                "1"));
    push.messageCommitted(event);
    scheduler.runReady();
    verify(fcm, times(1))
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void retryRespectsProviderDelayAndDoesNotResendAcceptedDevices() {
    active.add("device-2");
    when(subscriptions.activeTargets("owner"))
        .thenReturn(List.of(target("device-1"), target("device-2")));
    doReturn(new FcmClient.Result(false, "UNAVAILABLE", true, 120), ACCEPTED)
        .when(fcm)
        .sendPush(eq("fid-device-2"), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-retry"));
    scheduler.runReady();
    assertThat(scheduler.size()).isEqualTo(1);
    scheduler.advance(119);
    verify(fcm)
        .sendPush(eq("fid-device-2"), anyString(), anyString(), anyMap(), anyString(), any());
    scheduler.advance(1);
    verify(fcm, times(2))
        .sendPush(eq("fid-device-2"), anyString(), anyString(), anyMap(), anyString(), any());
    verify(fcm, times(1))
        .sendPush(eq("fid-device-1"), anyString(), anyString(), anyMap(), anyString(), any());
    assertThat(scheduler.size()).isZero();
    verify(history)
        .recordPushHistory(any(), eq("device-2"), eq(1), eq("QUEUED"), eq("UNAVAILABLE"), any());
    verify(history)
        .recordPushHistory(any(), eq("device-2"), eq(2), eq("ACCEPTED"), eq("ACCEPTED"), any());
  }

  @Test
  void transientTargetDiscoveryFailureIsRetriedBeforeFreezingTargets() {
    when(subscriptions.activeTargets("owner"))
        .thenThrow(new IllegalStateException("temporary lookup failure"))
        .thenReturn(List.of(target("device-1")));
    push.messageCommitted(event("msg-discovery"));
    scheduler.runReady();
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    scheduler.advance(60);
    verify(subscriptions, times(2)).activeTargets("owner");
    verify(fcm)
        .sendPush(eq("fid-device-1"), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void providerExceptionsRetryWithoutEscapingTheCommittedMessageCallback() {
    doThrow(new IllegalStateException("provider response contains private information"))
        .doReturn(ACCEPTED)
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    assertThatCode(() -> push.messageCommitted(event("msg-provider"))).doesNotThrowAnyException();
    assertThatCode(scheduler::runReady).doesNotThrowAnyException();
    scheduler.advance(60);
    verify(fcm, times(2))
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    assertThat(scheduler.size()).isZero();
  }

  @Test
  void noRegisteredRecipientAndExpiredOrDisabledMessagesAreSkipped() {
    when(subscriptions.activeTargets("owner")).thenReturn(List.of());
    push.messageCommitted(event("msg-no-fid"));
    scheduler.runReady();
    var expired = event("msg-expired");
    clock.advance(15 * 60);
    push.messageCommitted(expired);
    when(fcm.enabled()).thenReturn(false);
    push.messageCommitted(event("msg-disabled"));
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    assertThat(scheduler.size()).isZero();
    verify(history)
        .recordPushHistory(
            any(), isNull(), eq(0), eq("SKIPPED"), eq("NO_ACTIVE_SUBSCRIPTION"), any());
    verify(history).recordPushHistory(any(), isNull(), eq(0), eq("SKIPPED"), eq("EXPIRED"), any());
    verify(history).recordPushHistory(any(), isNull(), eq(0), eq("SKIPPED"), eq("DISABLED"), any());
  }

  @Test
  void failedAttemptHistoryPreventsSendingUntilTheDatabaseRecovers() {
    doThrow(new IllegalStateException("database unavailable"))
        .doNothing()
        .when(history)
        .recordPushHistory(any(), eq("device-1"), eq(1), eq("RUNNING"), isNull(), any());
    push.messageCommitted(event("audit-before-send"));
    scheduler.runReady();
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    scheduler.advance(60);
    var order = inOrder(history, fcm);
    order
        .verify(history, times(2))
        .recordPushHistory(any(), eq("device-1"), eq(1), eq("RUNNING"), isNull(), any());
    order.verify(fcm).sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    order
        .verify(history)
        .recordPushHistory(any(), eq("device-1"), eq(1), eq("ACCEPTED"), eq("ACCEPTED"), any());
    assertThat(scheduler.size()).isZero();
  }

  @Test
  void resultHistoryFailureDoesNotResendAnAcceptedPushOrLeakQueueCapacity() {
    doThrow(new IllegalStateException("database unavailable"))
        .when(history)
        .recordPushHistory(any(), anyString(), anyInt(), eq("ACCEPTED"), any(), any());
    var event = event("audit-after-send");
    push.messageCommitted(event);
    scheduler.runReady();
    push.messageCommitted(event);
    scheduler.advance(60);
    verify(fcm).sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    verify(history).recordPushHistory(any(), eq("device-1"), eq(1), eq("RUNNING"), isNull(), any());
    assertThat(scheduler.size()).isZero();
  }

  @Test
  void everyDeviceRejectedByCapacityHasASkipRecord() {
    push.shutdown();
    var limited = new ManualScheduler(clock);
    push = new ChatPushNotifications(chats, subscriptions, fcm, clock, limited, 1, history);
    when(subscriptions.activeTargets("owner"))
        .thenReturn(List.of(target("device-1"), target("device-2"), target("device-3")));
    push.messageCommitted(event("device-limit"));
    limited.runReady();
    verify(history)
        .recordPushHistory(any(), eq("device-2"), eq(0), eq("SKIPPED"), eq("QUEUE_FULL"), any());
    verify(history)
        .recordPushHistory(any(), eq("device-3"), eq(0), eq("SKIPPED"), eq("QUEUE_FULL"), any());
    verify(fcm).sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void readOrOwnershipChangeBeforeDeliverySuppressesTheEntireJob() {
    push.messageCommitted(event("msg-read"));
    eligible.set(false);
    scheduler.runReady();
    verifyNoInteractions(subscriptions);
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void logoutOrReadDuringCredentialRefreshFailsTheFinalSubmissionGuard() {
    var guards = new ArrayList<Boolean>();
    doAnswer(
            invocation -> {
              active.clear();
              guards.add(((BooleanSupplier) invocation.getArgument(5)).getAsBoolean());
              return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
            })
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-logout"));
    scheduler.runReady();
    assertThat(guards).containsExactly(false);
    active.add("device-1");
    doAnswer(
            invocation -> {
              eligible.set(false);
              guards.add(((BooleanSupplier) invocation.getArgument(5)).getAsBoolean());
              return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
            })
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-read-during-refresh"));
    scheduler.runReady();
    assertThat(guards).containsExactly(false, false);
    assertThat(scheduler.size()).isZero();
  }

  @Test
  void onlyConfirmedUnregisteredErrorsDeactivateTheDevice() {
    doReturn(
            new FcmClient.Result(false, "INVALID_ARGUMENT", false, 0),
            new FcmClient.Result(false, "UNREGISTERED", false, 0))
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-format-error"));
    scheduler.runReady();
    verify(subscriptions, never()).invalidate(anyString());
    push.messageCommitted(event("msg-unregistered"));
    scheduler.runReady();
    verify(subscriptions).invalidate("device-1");
  }

  @Test
  void retriesExpireWithinFifteenMinutesAndNeverRunForever() {
    doReturn(new FcmClient.Result(false, "UNAVAILABLE", true, 0))
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-always-unavailable"));
    scheduler.runReady();
    scheduler.advance(60);
    scheduler.advance(120);
    scheduler.advance(240);
    scheduler.advance(15 * 60);
    // The fifth exponential delay would reach the TTL, so it is discarded before scheduling.
    verify(fcm, times(4))
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    assertThat(scheduler.size()).isZero();
  }

  @Test
  void boundedAdmissionIncludesDeviceRetriesAndSchedulerRejectionIsSafe() {
    push.shutdown();
    var boundedScheduler = new ManualScheduler(clock);
    push =
        new ChatPushNotifications(chats, subscriptions, fcm, clock, boundedScheduler, 2, history);
    doReturn(new FcmClient.Result(false, "UNAVAILABLE", true, 60))
        .when(fcm)
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
    push.messageCommitted(event("msg-one"));
    push.messageCommitted(event("msg-two"));
    assertThatCode(() -> push.messageCommitted(event("msg-over-capacity")))
        .doesNotThrowAnyException();
    assertThat(boundedScheduler.size()).isEqualTo(2);
    boundedScheduler.runReady();
    push.messageCommitted(event("msg-still-full"));
    assertThat(boundedScheduler.size()).isEqualTo(2);
    boundedScheduler.shutdownNow();
    assertThatCode(() -> push.messageCommitted(event("msg-rejected"))).doesNotThrowAnyException();
    verify(fcm, times(2))
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  @Test
  void rejectedSchedulerDoesNotRunDatabaseOrProviderOnTheAckThread() {
    scheduler.shutdownNow();
    assertThatCode(() -> push.messageCommitted(event("msg-rejected"))).doesNotThrowAnyException();
    verifyNoInteractions(chats, subscriptions);
    verify(fcm, never())
        .sendPush(anyString(), anyString(), anyString(), anyMap(), anyString(), any());
  }

  private ChatService.PushRequested event(String id) {
    return event(id, "room-1", "예약 문의");
  }

  private ChatService.PushRequested event(String id, String room, String content) {
    return new ChatService.PushRequested(
        new ChatMessageResponse(
            id,
            room,
            1L,
            new ChatMessageResponse.Sender("customer", "CUSTOMER", "고객😀".repeat(20)),
            "client-" + id,
            "TEXT",
            content,
            OffsetDateTime.now(clock)),
        "owner");
  }

  private static NotificationSubscriptions.Target target(String id) {
    return new NotificationSubscriptions.Target(id, "fid-" + id);
  }

  private static final class MutableClock extends Clock {
    private Instant instant = Instant.parse("2026-10-16T10:00:00Z");

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

  private static final class ManualScheduler extends AbstractExecutorService
      implements ScheduledExecutorService {
    private final MutableClock clock;
    private final PriorityQueue<Task<?>> tasks = new PriorityQueue<>();
    private boolean stopped;
    private long order;

    ManualScheduler(MutableClock clock) {
      this.clock = clock;
    }

    int size() {
      return tasks.size();
    }

    void advance(long seconds) {
      clock.advance(seconds);
      runReady();
    }

    void runReady() {
      int executions = 0;
      while (!tasks.isEmpty() && tasks.peek().due <= clock.millis()) {
        assertThat(++executions).describedAs("bounded immediate work").isLessThan(1000);
        tasks.remove().run();
      }
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
      return schedule(Executors.callable(command), delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> command, long delay, TimeUnit unit) {
      if (stopped) throw new RejectedExecutionException();
      var task = new Task<V>(command, clock.millis() + unit.toMillis(delay), order++, clock);
      tasks.add(task);
      return task;
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable command, long initial, long period, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable command, long initial, long delay, TimeUnit unit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void shutdown() {
      stopped = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
      stopped = true;
      tasks.clear();
      return List.of();
    }

    @Override
    public boolean isShutdown() {
      return stopped;
    }

    @Override
    public boolean isTerminated() {
      return stopped && tasks.isEmpty();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return isTerminated();
    }

    @Override
    public void execute(Runnable command) {
      schedule(command, 0, TimeUnit.MILLISECONDS);
    }
  }

  private static final class Task<V> extends FutureTask<V> implements ScheduledFuture<V> {
    final long due;
    final long order;
    final Clock clock;

    Task(Callable<V> command, long due, long order, Clock clock) {
      super(command);
      this.due = due;
      this.order = order;
      this.clock = clock;
    }

    @Override
    public long getDelay(TimeUnit unit) {
      return unit.convert(due - clock.millis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
      var task = (Task<?>) other;
      int comparison = Long.compare(due, task.due);
      return comparison == 0 ? Long.compare(order, task.order) : comparison;
    }
  }
}
