package com.bintime.notification.stream;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.notification.repository.NotificationRepository;
import com.bintime.notification.service.NotificationService;
import com.bintime.user.security.BintimePrincipal;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
@Profile("!api-docs")
public class NotificationStream implements HttpSessionListener {
  private final NotificationRepository db;
  private final Clock clock;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();
  private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService timer =
      Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());

  public NotificationStream(NotificationRepository db, Clock clock) {
    this.db = db;
    this.clock = clock;
    timer.scheduleWithFixedDelay(this::poll, 1, 1, TimeUnit.SECONDS);
  }

  public SseEmitter open(
      BintimePrincipal principal, HttpSession session, String lastEventId, String afterEventId) {
    String user = NotificationService.user(principal);
    require(validSession(session, principal), 401, "UNAUTHORIZED");
    // A supplied header always wins, including an invalid header that must not fall back silently.
    String cursor = lastEventId != null ? lastEventId : afterEventId;
    long sequence;
    if (cursor == null) sequence = db.latestSequence(user);
    else {
      var event = db.event(user, cursor);
      require(event != null, 400, "INVALID_STREAM_CURSOR");
      require(
          !event.createdAt().isBefore(OffsetDateTime.now(clock).minusHours(24)),
          410,
          "STREAM_CURSOR_EXPIRED");
      sequence = event.sequence();
    }
    var emitter = new SseEmitter(0L);
    String id = UUID.randomUUID().toString();
    var connection =
        new Connection(
            id,
            principal,
            session,
            emitter,
            sequence,
            clock.millis(),
            OffsetDateTime.now(clock).minusHours(24));
    emitter.onCompletion(() -> connections.remove(id));
    emitter.onTimeout(() -> connections.remove(id));
    emitter.onError(error -> connections.remove(id));
    connections.put(id, connection);
    try {
      emitter.send(SseEmitter.event().reconnectTime(3000).comment("connected"));
    } catch (IOException failure) {
      connections.remove(id);
      emitter.completeWithError(failure);
    }
    return emitter;
  }

  void poll() {
    for (Connection connection : connections.values()) {
      if (!connection.sending.compareAndSet(false, true)) continue;
      senders.execute(
          () -> {
            try {
              send(connection);
            } finally {
              connection.sending.set(false);
            }
          });
    }
  }

  private void send(Connection connection) {
    try {
      if (!validSession(connection.session, connection.principal)) {
        expire(connection);
        return;
      }
      var now = OffsetDateTime.now(clock);
      var events =
          db.events(connection.principal.userId(), connection.sequence, connection.replaySince);
      for (var event : events) {
        if (!validSession(connection.session, connection.principal)) {
          expire(connection);
          return;
        }
        connection.emitter.send(
            SseEmitter.event()
                .id(event.id())
                .name(event.type())
                .data(event.json(), MediaType.APPLICATION_JSON));
        connection.sequence = event.sequence();
      }
      if (clock.millis() - connection.lastHeartbeat >= 15_000) {
        connection.emitter.send(SseEmitter.event().comment("heartbeat"));
        connection.lastHeartbeat = clock.millis();
      }
    } catch (IOException | RuntimeException failure) {
      connections.remove(connection.id);
      connection.emitter.completeWithError(failure);
    }
  }

  private boolean validSession(HttpSession session, BintimePrincipal principal) {
    if (session == null) return false;
    try {
      Object value =
          session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      if (!(value instanceof SecurityContext context)
          || context.getAuthentication() == null
          || !context.getAuthentication().isAuthenticated()
          || !principal.equals(context.getAuthentication().getPrincipal())) return false;
      int seconds = session.getMaxInactiveInterval();
      return seconds <= 0 || clock.millis() - session.getLastAccessedTime() < seconds * 1_000L;
    } catch (IllegalStateException expired) {
      return false;
    }
  }

  @Override
  public void sessionDestroyed(HttpSessionEvent event) {
    for (Connection connection : connections.values()) {
      if (connection.session.getId().equals(event.getSession().getId())) expire(connection);
    }
  }

  private void expire(Connection connection) {
    if (!connections.remove(connection.id, connection)) return;
    try {
      connection.emitter.send(
          SseEmitter.event()
              .name("session.expired")
              .data("{\"reason\":\"SESSION_EXPIRED\"}", MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException ignored) {
      // The peer may already have closed its side of the stream.
    }
    connection.emitter.complete();
  }

  @PreDestroy
  public void shutdown() {
    timer.shutdownNow();
    senders.shutdownNow();
    for (Connection connection : connections.values()) connection.emitter.complete();
    connections.clear();
  }

  private static final class Connection {
    final String id;
    final BintimePrincipal principal;
    final HttpSession session;
    final SseEmitter emitter;
    final AtomicBoolean sending = new AtomicBoolean();
    final OffsetDateTime replaySince;
    long sequence;
    long lastHeartbeat;

    Connection(
        String id,
        BintimePrincipal principal,
        HttpSession session,
        SseEmitter emitter,
        long sequence,
        long now,
        OffsetDateTime replaySince) {
      this.id = id;
      this.principal = principal;
      this.session = session;
      this.emitter = emitter;
      this.sequence = sequence;
      this.lastHeartbeat = now;
      this.replaySince = replaySince;
    }
  }

  @Configuration(proxyBeanMethods = false)
  @Profile("!api-docs")
  static class SessionEvents {
    @Bean
    ServletListenerRegistrationBean<NotificationStream> notificationStreamSessionListener(
        NotificationStream stream) {
      return new ServletListenerRegistrationBean<>(stream);
    }
  }
}
