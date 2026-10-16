package com.bintime.notification.service;

import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.repository.NotificationRepository.Delivery;
import com.bintime.notification.subscription.NotificationSubscriptions;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@Profile("!api-docs")
public class NotificationDeliveryWorker {
  private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);
  private final NotificationRepository db;
  private final NotificationService notifications;
  private final NotificationSubscriptions subscriptions;
  private final FcmClient fcm;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final long minimumIntervalMillis;
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

  public NotificationDeliveryWorker(
      NotificationRepository db,
      NotificationService notifications,
      NotificationSubscriptions subscriptions,
      FcmClient fcm,
      TransactionTemplate tx,
      Clock clock,
      @Value("${notification.delivery.minimum-interval-seconds:1}") long minimumIntervalSeconds,
      @Value("${notification.scheduling-enabled:true}") boolean schedulingEnabled) {
    this.db = db;
    this.notifications = notifications;
    this.subscriptions = subscriptions;
    this.fcm = fcm;
    this.tx = tx;
    this.clock = clock;
    this.minimumIntervalMillis = Math.max(0, minimumIntervalSeconds) * 1_000;
    if (schedulingEnabled)
      scheduler.scheduleWithFixedDelay(this::runSafely, 5, 5, TimeUnit.SECONDS);
  }

  public void run() {
    // Unconfigured credentials must leave the durable queue available for later configuration.
    if (!fcm.enabled()) return;
    for (String id : db.due(clock.millis())) {
      Delivery delivery = db.claim(id, clock.millis());
      if (delivery == null) continue;
      try {
        deliver(delivery);
      } catch (RuntimeException failure) {
        log.warn(
            "Notification delivery {} failed: {}",
            delivery.id(),
            failure.getClass().getSimpleName());
        retry(delivery, "DEPENDENCY_UNAVAILABLE", 60);
      }
    }
  }

  private void deliver(Delivery delivery) {
    var alert = db.find(delivery.userId(), delivery.notificationId());
    if (alert == null || !alert.expiresAt().toInstant().isAfter(clock.instant())) {
      finish(delivery, "SKIPPED", "EXPIRED", 0, false);
      return;
    }
    if (delivery.attempts() > 5) {
      finish(delivery, "FAILED", "RETRY_LIMIT", 0, false);
      return;
    }
    long now = clock.millis();
    long available =
        tx.execute(
            status -> {
              if (!db.lockUser(delivery.userId())) return Long.MAX_VALUE;
              return db.reserveSendWindow(delivery.userId(), now, minimumIntervalMillis);
            });
    if (available > now) {
      finish(delivery, "QUEUED", "RATE_LIMITED", available, false);
      return;
    }
    var submitted =
        subscriptions.withActiveTarget(
            delivery.userId(),
            delivery.subscriptionId(),
            target -> {
              // Public search/store checks run outside a notification transaction immediately
              // before submission.
              if (notifications.eligible(alert.request()) == null) {
                return new FcmClient.Result(false, "STALE_CONDITIONS", false, 0);
              }
              // Validation can be slow. Re-enter the lifecycle guard to expire stale sessions
              // immediately
              // before FCM; its lock is reentrant and no search database lock survives validation.
              var finalTarget =
                  subscriptions.withActiveTarget(
                      delivery.userId(),
                      target.id(),
                      active ->
                          fcm.send(
                              active.installationId(),
                              alert.notification(),
                              () ->
                                  notifications.eligible(alert.request()) != null
                                      && subscriptions
                                          .withActiveTarget(
                                              delivery.userId(),
                                              active.id(),
                                              ignored -> Boolean.TRUE)
                                          .submitted()));
              if (!finalTarget.submitted())
                return new FcmClient.Result(false, "INACTIVE_SUBSCRIPTION", false, 0);
              var result = finalTarget.value();
              if ("UNREGISTERED".equals(result.code())) subscriptions.invalidate(target.id());
              return result;
            });
    if (!submitted.submitted()) {
      finish(delivery, "SKIPPED", "INACTIVE_SUBSCRIPTION", 0, false);
      return;
    }
    var result = submitted.value();
    if (result.accepted()) finish(delivery, "ACCEPTED", null, 0, true);
    else if ("DISABLED".equals(result.code())) {
      finish(delivery, "QUEUED", result.code(), clock.millis() + 60_000, false);
    } else if ("STALE_CONDITIONS".equals(result.code())
        || "INACTIVE_SUBSCRIPTION".equals(result.code())) {
      // The final FCM guard also suppresses conditions that changed during credential refresh.
      finish(delivery, "SKIPPED", result.code(), 0, false);
    } else if (result.retryable()) retry(delivery, result.code(), result.retryAfterSeconds());
    else finish(delivery, "FAILED", result.code(), 0, true);
  }

  private void retry(Delivery delivery, String code, long retryAfterSeconds) {
    long delay = Math.max(retryAfterSeconds, 60L << Math.min(delivery.attempts() - 1, 4));
    long now = clock.millis();
    long available = now + Math.min(delay, (Long.MAX_VALUE - now) / 1_000) * 1_000;
    finish(delivery, delivery.attempts() >= 5 ? "FAILED" : "QUEUED", code, available, true);
  }

  private void finish(
      Delivery delivery, String status, String code, long available, boolean attempted) {
    tx.executeWithoutResult(
        transaction -> {
          // Serialize the aggregate with other devices finishing the same user's alert.
          db.lockUser(delivery.userId());
          db.finish(delivery, status, code, available, attempted, OffsetDateTime.now(clock));
        });
  }

  private void runSafely() {
    try {
      run();
    } catch (RuntimeException failure) {
      log.warn("Notification worker failed: {}", failure.getClass().getSimpleName());
    }
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }
}
