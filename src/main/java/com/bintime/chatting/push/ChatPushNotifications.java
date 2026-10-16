package com.bintime.chatting.push;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.repository.ChatRepository;
import com.bintime.chatting.service.ChatService;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.subscription.NotificationSubscriptions;
import jakarta.annotation.PreDestroy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
      ChatRepository chats, NotificationSubscriptions subscriptions, FcmClient fcm, Clock clock) {
    this(chats, subscriptions, fcm, clock, scheduler(), 512);
  }

  ChatPushNotifications(
      ChatRepository chats,
      NotificationSubscriptions subscriptions,
      FcmClient fcm,
      Clock clock,
      ScheduledExecutorService scheduler,
      int capacity) {
    this.chats = chats;
    this.subscriptions = subscriptions;
    this.fcm = fcm;
    this.clock = clock;
    this.scheduler = scheduler;
    this.capacity = capacity;
  }

  @TransactionalEventListener
  public void messageCommitted(ChatService.PushRequested event) {
    Job job = null;
    try {
      if (!fcm.enabled()) return;
      var message = event.message();
      if (event.recipientId().equals(message.sender().id())) return;
      Instant expires = message.createdAt().toInstant().plus(TTL);
      if (!expires.isAfter(clock.instant())) return;
      synchronized (pending) {
        completed.values().removeIf(expiry -> !expiry.isAfter(clock.instant()));
        if (stopped || completed.containsKey(message.id()) || pending.containsKey(message.id()))
          return;
        if (reserved >= capacity) {
          log.warn("Chat push queue is full; message={}", message.id());
          return;
        }
        job = new Job(message, event.recipientId(), expires);
        pending.put(message.id(), job);
        reserved++;
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
        finish(job, true);
        return;
      }
      if (job.targets == null) {
        var targets = subscriptions.activeTargets(job.recipient);
        synchronized (pending) {
          if (stopped || pending.get(job.message.id()) != job) return;
          job.targets = new LinkedHashMap<>();
          for (var target : targets) {
            // The discovery reservation covers the first device; retries share the same budget.
            if (!job.targets.isEmpty()) {
              if (reserved >= capacity) {
                log.warn("Chat push device queue is full; message={}", job.message.id());
                break;
              }
              reserved++;
              job.weight++;
            }
            job.targets.put(target.id(), new Attempt());
          }
        }
      }
      long now = clock.millis();
      for (Iterator<Map.Entry<String, Attempt>> iterator = job.targets.entrySet().iterator();
          iterator.hasNext(); ) {
        var entry = iterator.next();
        if (entry.getValue().availableAt > now) continue;
        if (!active(job)) return;
        if (expired(job) || !eligible(job)) {
          finish(job, true);
          return;
        }
        var attempt = entry.getValue();
        attempt.count++;
        FcmClient.Result result;
        try {
          var delivery =
              subscriptions.withActiveTarget(
                  job.recipient,
                  entry.getKey(),
                  target ->
                      fcm.sendPush(
                          target.installationId(),
                          preview(job.message.sender().displayName(), 40) + "님의 새 메시지",
                          preview(job.message.content(), 120),
                          Map.of(
                              "type", "CHAT_MESSAGE",
                              "roomId", job.message.roomId(),
                              "messageId", job.message.id(),
                              "notificationId", job.message.id(),
                              "sequence", Long.toString(job.message.sequence())),
                          "/chat?roomId="
                              + URLEncoder.encode(job.message.roomId(), StandardCharsets.UTF_8),
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
        finish(job, true);
      } else {
        long delay = 60_000L << (job.failures - 1);
        if (delay >= Duration.between(clock.instant(), job.expires).toMillis()) finish(job, true);
        else schedule(job, delay);
      }
    }
  }

  private boolean eligible(Job job) {
    return chats.pushEligible(job.message, job.recipient);
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
      finish(job, false);
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
