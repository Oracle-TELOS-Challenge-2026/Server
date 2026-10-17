package com.bintime.chatting.push;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.repository.NotificationRepository.Push;
import com.bintime.notification.subscription.NotificationSubscriptions;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@Profile("!api-docs")
public class ChatPushNotifications {
  private static final Logger log = LoggerFactory.getLogger(ChatPushNotifications.class);
  private static final Duration TTL = Duration.ofMinutes(15);
  private static final int MAX_ATTEMPTS = 5;
  private final ChatRepository chats;
  private final NotificationSubscriptions subscriptions;
  private final FcmClient fcm;
  private final NotificationRepository history;
  private final Clock clock;
  private final ScheduledExecutorService scheduler;
  private final int capacity;
  // shortcut: restart-safe push needs persistent sessions and an outbox; current jobs stay
  // in-process.
  private final Map<String, Job> pending = new HashMap<>();
  private final Map<String, Instant> completed = new LinkedHashMap<>();
  private int reserved;
  private boolean stopped;

  @Autowired
  public ChatPushNotifications(
      ChatRepository chats,
      NotificationSubscriptions subscriptions,
      FcmClient fcm,
      Clock clock,
      NotificationRepository history) {
    this(chats, subscriptions, fcm, clock, scheduler(), 512, history);
  }

  ChatPushNotifications(
      ChatRepository chats,
      NotificationSubscriptions subscriptions,
      FcmClient fcm,
      Clock clock,
      ScheduledExecutorService scheduler,
      int capacity,
      NotificationRepository history) {
    this.chats = chats;
    this.subscriptions = subscriptions;
    this.fcm = fcm;
    this.clock = clock;
    this.scheduler = scheduler;
    this.capacity = capacity;
    this.history = history;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void messageRequested(ChatService.PushRequested event) {
    if (!event.recipientId().equals(event.message().sender().id()))
      history.appendPushHistory(
          push(event.message(), event.recipientId()),
          null,
          0,
          "QUEUED",
          null,
          OffsetDateTime.now(clock));
  }

  @TransactionalEventListener
  public void messageCommitted(ChatService.PushRequested event) {
    Job job = null;
    try {
      var message = event.message();
      if (event.recipientId().equals(message.sender().id())) return;
      Instant expires = message.createdAt().toInstant().plus(TTL);
      if (!fcm.enabled() || !expires.isAfter(clock.instant())) {
        history.recordPushHistory(
            push(message, event.recipientId()),
            null,
            0,
            "SKIPPED",
            !fcm.enabled() ? "DISABLED" : "EXPIRED",
            OffsetDateTime.now(clock));
        return;
      }
      String rejection = null;
      synchronized (pending) {
        completed.values().removeIf(expiry -> !expiry.isAfter(clock.instant()));
        if (completed.containsKey(message.id()) || pending.containsKey(message.id())) return;
        if (stopped || reserved >= capacity) {
          rejection = stopped ? "APP_SHUTDOWN" : "QUEUE_FULL";
          log.warn("Chat push queue is full; message={}", message.id());
        } else {
          job = new Job(message, event.recipientId(), expires);
          pending.put(message.id(), job);
          reserved++;
        }
      }
      if (rejection != null) {
        history.recordPushHistory(
            push(message, event.recipientId()),
            null,
            0,
            "SKIPPED",
            rejection,
            OffsetDateTime.now(clock));
        return;
      }
      schedule(job, 0);
    } catch (RuntimeException failure) {
      // The message has already committed: queue failure must never fail its HTTP/WS ACK.
      if (job != null) finish(job, false);
      log.warn("Chat push could not be queued: {}", failure.getClass().getSimpleName());
    }
  }

  private void run(Job job) {
    if (!active(job)) return;
    try {
      if (!fcm.enabled() || expired(job) || !eligible(job)) {
        skip(
            job,
            !fcm.enabled() ? "DISABLED" : expired(job) ? "EXPIRED" : "READ_OR_NOT_PARTICIPANT");
        return;
      }
      if (job.targets == null) {
        var targets = subscriptions.activeTargets(job.recipient);
        var skipped = new ArrayList<String>();
        synchronized (pending) {
          if (stopped || pending.get(job.message.id()) != job) return;
          job.targets = new LinkedHashMap<>();
          for (var target : targets) {
            // The discovery reservation covers the first device; retries share the same budget.
            if (!job.targets.isEmpty()) {
              if (reserved >= capacity) {
                skipped.add(target.id());
                continue;
              }
              reserved++;
              job.weight++;
            }
            job.targets.put(target.id(), new Attempt());
          }
        }
        for (var id : skipped) recordResult(job, id, 0, "SKIPPED", "QUEUE_FULL");
        if (job.targets.isEmpty()) {
          skip(job, "NO_ACTIVE_SUBSCRIPTION");
          return;
        }
      }
      long now = clock.millis();
      for (Iterator<Map.Entry<String, Attempt>> iterator = job.targets.entrySet().iterator();
          iterator.hasNext(); ) {
        var entry = iterator.next();
        if (entry.getValue().availableAt > now) continue;
        if (!active(job)) return;
        if (expired(job) || !eligible(job)) {
          skip(job, expired(job) ? "EXPIRED" : "READ_OR_NOT_PARTICIPANT");
          return;
        }
        var attempt = entry.getValue();
        // DB가 기록을 받을 수 없으면 FCM부터 보내지 않는다.
        record(job, entry.getKey(), attempt.count + 1, "RUNNING", null);
        attempt.count++;
        FcmClient.Result result;
        var payload = push(job.message, job.recipient);
        try {
          var delivery =
              subscriptions.withActiveTarget(
                  job.recipient,
                  entry.getKey(),
                  target ->
                      fcm.sendPush(
                          target.installationId(),
                          payload.title(),
                          payload.body(),
                          Map.of(
                              "type", "CHAT_MESSAGE",
                              "roomId", job.message.roomId(),
                              "messageId", job.message.id(),
                              "notificationId", job.message.id(),
                              "sequence", Long.toString(job.message.sequence())),
                          payload.path(),
                          () ->
                              active(job)
                                  && !expired(job)
                                  && eligible(job)
                                  && subscriptions
                                      .withActiveTarget(
                                          job.recipient, target.id(), ignored -> Boolean.TRUE)
                                      .submitted()));
          result =
              delivery.submitted()
                  ? delivery.value()
                  : new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
        } catch (RuntimeException failure) {
          // Dependency failures are retried without logging FIDs, message text, or provider bodies.
          result = new FcmClient.Result(false, "DEPENDENCY_UNAVAILABLE", true, 60);
        }
        String resultStatus =
            result.accepted()
                ? "ACCEPTED"
                : "INACTIVE_SUBSCRIPTION".equals(result.code()) || "DISABLED".equals(result.code())
                    ? "SKIPPED"
                    : result.retryable() && attempt.count < MAX_ATTEMPTS ? "QUEUED" : "FAILED";
        recordResult(job, entry.getKey(), attempt.count, resultStatus, result.code());
        if ("UNREGISTERED".equals(result.code())) {
          try {
            subscriptions.invalidate(entry.getKey());
          } catch (RuntimeException failure) {
            log.warn(
                "Chat push subscription cleanup failed: {}", failure.getClass().getSimpleName());
          }
        }
        if (result.accepted() || !result.retryable() || attempt.count >= MAX_ATTEMPTS) {
          if (!result.accepted()
              && !"INACTIVE_SUBSCRIPTION".equals(result.code())
              && !"UNREGISTERED".equals(result.code()))
            log.warn(
                "Chat push delivery ended; message={}, code={}", job.message.id(), result.code());
          iterator.remove();
          releaseDevice(job);
        } else {
          long seconds = Math.max(result.retryAfterSeconds(), 60L << (attempt.count - 1));
          // A retry beyond this message's useful lifetime is discarded rather than retained.
          long remaining = Duration.between(clock.instant(), job.expires).toSeconds();
          if (seconds >= remaining) {
            recordResult(job, entry.getKey(), attempt.count, "SKIPPED", "RETRY_EXPIRED");
            log.warn(
                "Chat push retry expired; message={}, code={}", job.message.id(), result.code());
            iterator.remove();
            releaseDevice(job);
          } else attempt.availableAt = clock.millis() + seconds * 1000;
        }
      }
      if (job.targets.isEmpty()) finish(job, true);
      else {
        long due =
            job.targets.values().stream()
                .mapToLong(attempt -> attempt.availableAt)
                .min()
                .orElseThrow();
        schedule(job, Math.max(0, due - clock.millis()));
      }
    } catch (RuntimeException failure) {
      if (++job.failures >= MAX_ATTEMPTS || expired(job)) {
        log.warn(
            "Chat push dependency retry ended; message={}, error={}",
            job.message.id(),
            failure.getClass().getSimpleName());
        skip(job, expired(job) ? "EXPIRED" : "DEPENDENCY_UNAVAILABLE");
      } else {
        long delay = 60_000L << (job.failures - 1);
        if (delay >= Duration.between(clock.instant(), job.expires).toMillis())
          skip(job, "RETRY_EXPIRED");
        else schedule(job, delay);
      }
    }
  }

  private boolean eligible(Job job) {
    return chats.pushEligible(job.message, job.recipient);
  }

  private static Push push(ChatMessageResponse message, String recipient) {
    return new Push(
        "CHAT_MESSAGE",
        message.id(),
        recipient,
        preview(message.sender().displayName(), 40) + "님의 새 메시지",
        preview(message.content(), 120),
        "/chat?roomId=" + URLEncoder.encode(message.roomId(), StandardCharsets.UTF_8));
  }

  private void record(Job job, String subscription, int attempt, String status, String code) {
    history.recordPushHistory(
        push(job.message, job.recipient),
        subscription,
        attempt,
        status,
        code,
        OffsetDateTime.now(clock));
  }

  private void skip(Job job, String code) {
    if (job.targets == null || job.targets.isEmpty()) recordResult(job, null, 0, "SKIPPED", code);
    else
      for (var entry : job.targets.entrySet())
        recordResult(job, entry.getKey(), entry.getValue().count, "SKIPPED", code);
    finish(job, true);
  }

  private void recordResult(Job job, String subscription, int attempt, String status, String code) {
    try {
      record(job, subscription, attempt, status, code);
    } catch (RuntimeException unavailable) {
      // 접수 후 이력 저장 실패로 재발송하지 않는다. RUNNING은 결과 미확인이다.
      log.warn("Chat push result history unavailable; message={}", job.message.id());
    }
  }

  private boolean expired(Job job) {
    return !job.expires.isAfter(clock.instant());
  }

  private boolean active(Job job) {
    synchronized (pending) {
      return !stopped && pending.get(job.message.id()) == job;
    }
  }

  private void schedule(Job job, long delayMillis) {
    try {
      scheduler.schedule(() -> run(job), delayMillis, TimeUnit.MILLISECONDS);
    } catch (RuntimeException rejected) {
      skip(job, "SCHEDULER_REJECTED");
      log.warn("Chat push scheduling failed: {}", rejected.getClass().getSimpleName());
    }
  }

  private void finish(Job job, boolean remember) {
    synchronized (pending) {
      if (!pending.remove(job.message.id(), job)) return;
      reserved -= job.weight;
      if (remember) {
        completed.put(job.message.id(), job.expires);
        if (completed.size() > 4096) completed.remove(completed.keySet().iterator().next());
      }
    }
  }

  private void releaseDevice(Job job) {
    synchronized (pending) {
      if (pending.get(job.message.id()) == job && job.weight > 1) {
        job.weight--;
        reserved--;
      }
    }
  }

  private static String preview(String value, int limit) {
    if (value == null) return "";
    int points = value.codePointCount(0, value.length());
    return points <= limit
        ? value
        : value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
  }

  private static ScheduledExecutorService scheduler() {
    var executor =
        new ScheduledThreadPoolExecutor(
            2, Thread.ofPlatform().daemon().name("chat-push-", 0).factory());
    executor.setRemoveOnCancelPolicy(true);
    executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    return executor;
  }

  @PreDestroy
  public void shutdown() {
    synchronized (pending) {
      stopped = true;
      pending.clear();
      completed.clear();
      reserved = 0;
    }
    scheduler.shutdownNow();
  }

  private static final class Job {
    final ChatMessageResponse message;
    final String recipient;
    final Instant expires;
    Map<String, Attempt> targets;
    int failures;
    int weight = 1;

    Job(ChatMessageResponse message, String recipient, Instant expires) {
      this.message = message;
      this.recipient = recipient;
      this.expires = expires;
    }
  }

  private static final class Attempt {
    int count;
    long availableAt;
  }
}
