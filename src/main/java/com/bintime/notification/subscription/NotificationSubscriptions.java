package com.bintime.notification.subscription;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.notification.dto.PushSubscriptionResponse;
import com.bintime.user.security.BintimePrincipal;
import jakarta.servlet.http.HttpSession;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Lazy
@Profile("!api-docs")
public class NotificationSubscriptions {
  private static final String SESSION_BINDING =
      NotificationSubscriptions.class.getName() + ".binding";
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final String projectId;
  private final String instanceId = UUID.randomUUID().toString();
  private final Map<String, LiveSession> sessions = new HashMap<>();
  // 단일 앱의 세션 수명과 발송 제출을 직렬화한다. 여러 앱 운영 시 공유 세션·분산 잠금이 필요하다.
  private final ReentrantLock lifecycle = new ReentrantLock();

  public NotificationSubscriptions(
      JdbcClient jdbc,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${bintime.fcm.project-id:bintime-33512}") String projectId) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactionManager);
    this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.projectId = projectId;
  }

  public record Registration(PushSubscriptionResponse subscription, boolean created) {}

  public record Target(String id, String installationId) {}

  public record Delivery<T>(boolean submitted, T value) {}

  private record LiveSession(HttpSession session, String userId) {}

  private record Stored(String id, String userId, OffsetDateTime createdAt, boolean active) {}

  public Registration register(
      BintimePrincipal principal, HttpSession session, String installationId) {
    String userId = user(principal);
    require(
        installationId != null
            && !installationId.isEmpty()
            && installationId.length() <= 128
            && installationId
                .codePoints()
                .noneMatch(
                    c ->
                        Character.isWhitespace(c)
                            || Character.isSpaceChar(c)
                            || Character.isISOControl(c)),
        400,
        "INVALID_INSTALLATION_ID");
    return locked(
        () -> {
          require(valid(new LiveSession(session, userId)), 401, "UNAUTHENTICATED");
          String token = (String) session.getAttribute(SESSION_BINDING);
          if (token == null) {
            token = UUID.randomUUID().toString();
            session.setAttribute(SESSION_BINDING, token);
          }
          String binding = token;
          LiveSession previous = sessions.get(binding);
          try {
            return transaction(
                () -> {
                  prune();
                  sessions.put(binding, new LiveSession(session, userId));
                  Stored active =
                      find(
                          "fcm_project_id = ? AND installation_id = ? AND active_yn = 'Y'",
                          projectId,
                          installationId);
                  require(
                      active == null || active.userId().equals(userId), 409, "SUBSCRIPTION_IN_USE");
                  Stored stored =
                      find(
                          "user_id = ? AND fcm_project_id = ? AND installation_id = ?",
                          userId,
                          projectId,
                          installationId);
                  boolean created = stored == null;
                  OffsetDateTime now = now();
                  if (created) {
                    stored =
                        new Stored(
                            "push_" + UUID.randomUUID().toString().replace("-", ""),
                            userId,
                            now,
                            true);
                    jdbc.sql(
                            """
                            INSERT INTO notification_subscriptions
                              (subscription_id, user_id, fcm_project_id, installation_id, active_yn, created_at, updated_at)
                            VALUES (?, ?, ?, ?, 'Y', ?, ?)
                            """)
                        .params(stored.id(), userId, projectId, installationId, now, now)
                        .update();
                  } else {
                    jdbc.sql(
                            "UPDATE notification_subscriptions SET active_yn = 'Y', updated_at = ?"
                                + " WHERE subscription_id = ?")
                        .params(now, stored.id())
                        .update();
                  }
                  // 현재 세션의 이전 FID만 교체한다. 다른 세션이 공유하던 FID는 유지한다.
                  jdbc.sql("DELETE FROM notification_subscription_sessions WHERE binding_id = ?")
                      .param(binding)
                      .update();
                  jdbc.sql(
                          "INSERT INTO notification_subscription_sessions (binding_id, instance_id,"
                              + " subscription_id) VALUES (?, ?, ?)")
                      .params(binding, instanceId, stored.id())
                      .update();
                  deactivateOrphans();
                  require(valid(new LiveSession(session, userId)), 401, "UNAUTHENTICATED");
                  return new Registration(
                      new PushSubscriptionResponse(stored.id(), stored.createdAt(), null), created);
                });
          } catch (RuntimeException failure) {
            if (previous == null) sessions.remove(binding);
            else sessions.put(binding, previous);
            throw failure;
          }
        });
  }

  public void delete(BintimePrincipal principal, String subscriptionId) {
    String userId = user(principal);
    locked(
        () ->
            transaction(
                () -> {
                  prune();
                  Stored stored = find("subscription_id = ?", subscriptionId);
                  if (stored != null && stored.active()) {
                    require(stored.userId().equals(userId), 404, "NOT_FOUND");
                    deactivate(subscriptionId);
                  }
                  return null;
                }));
  }

  public boolean hasActive(String userId) {
    return !activeTargets(userId).isEmpty();
  }

  public List<Target> activeTargets(String userId) {
    return locked(
        () ->
            transaction(
                () -> {
                  prune();
                  return targets(userId, null);
                }));
  }

  public <T> Delivery<T> withActiveTarget(
      String userId, String subscriptionId, Function<Target, T> submission) {
    return locked(
        () -> {
          List<Target> active =
              transaction(
                  () -> {
                    prune();
                    return targets(userId, subscriptionId);
                  });
          if (active.isEmpty()) return new Delivery<>(false, null);
          // DB 트랜잭션을 끝낸 뒤 제출한다. 해제·로그아웃은 이 발송 제출이 끝난 후 완료된다.
          return new Delivery<>(true, submission.apply(active.getFirst()));
        });
  }

  /** FCM이 UNREGISTERED를 확인했을 때만 호출한다. 일반 형식 오류로 구독을 지우지 않는다. */
  public void invalidate(String subscriptionId) {
    locked(
        () ->
            transaction(
                () -> {
                  deactivate(subscriptionId);
                  return null;
                }));
  }

  /** 로그아웃 필터는 세션 폐기 전에 호출해서 DB 정리 실패를 성공으로 숨기지 않는다. */
  public void releaseSession(HttpSession session) {
    if (session == null) return;
    locked(
        () -> {
          List<String> removed =
              sessions.entrySet().stream()
                  .filter(entry -> entry.getValue().session() == session)
                  .map(Map.Entry::getKey)
                  .toList();
          // 저장 실패 시에도 메모리에서 제외하므로 다음 발송에서 오래된 연결을 다시 쓰지 않는다.
          removed.forEach(sessions::remove);
          if (!removed.isEmpty())
            transaction(
                () -> {
                  for (String binding : removed)
                    jdbc.sql("DELETE FROM notification_subscription_sessions WHERE binding_id = ?")
                        .param(binding)
                        .update();
                  deactivateOrphans();
                  return null;
                });
          return null;
        });
  }

  private List<Target> targets(String userId, String subscriptionId) {
    String sql =
        """
        SELECT s.subscription_id, s.installation_id FROM notification_subscriptions s
        WHERE s.user_id = :user AND s.fcm_project_id = :project AND s.active_yn = 'Y'
          AND EXISTS (SELECT 1 FROM notification_subscription_sessions b
                      WHERE b.subscription_id = s.subscription_id AND b.instance_id = :instance)
        """;
    if (subscriptionId != null) sql += " AND s.subscription_id = :id";
    var query =
        jdbc.sql(sql)
            .param("user", userId)
            .param("project", projectId)
            .param("instance", instanceId);
    if (subscriptionId != null) query = query.param("id", subscriptionId);
    return query.query((rs, row) -> new Target(rs.getString(1), rs.getString(2))).list();
  }

  private Stored find(String where, Object... parameters) {
    return jdbc.sql(
            "SELECT subscription_id, user_id, created_at, active_yn FROM notification_subscriptions"
                + " WHERE "
                + where)
        .params(parameters)
        .query(
            (rs, row) ->
                new Stored(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getObject(3, OffsetDateTime.class),
                    "Y".equals(rs.getString(4))))
        .optional()
        .orElse(null);
  }

  private void prune() {
    // 서버 세션은 메모리 세션이다. 재시작 전의 연결을 현재 로그인으로 복원하지 않는다.
    jdbc.sql("DELETE FROM notification_subscription_sessions WHERE instance_id <> ?")
        .param(instanceId)
        .update();
    for (String binding : new ArrayList<>(sessions.keySet())) {
      if (!valid(sessions.get(binding))) sessions.remove(binding);
    }
    for (String binding :
        jdbc.sql("SELECT binding_id FROM notification_subscription_sessions WHERE instance_id = ?")
            .param(instanceId)
            .query(String.class)
            .list()) {
      if (!sessions.containsKey(binding))
        jdbc.sql("DELETE FROM notification_subscription_sessions WHERE binding_id = ?")
            .param(binding)
            .update();
    }
    deactivateOrphans();
  }

  private void deactivateOrphans() {
    jdbc.sql(
            """
            UPDATE notification_subscriptions s SET active_yn = 'N', updated_at = ?
            WHERE s.fcm_project_id = ? AND s.active_yn = 'Y'
              AND NOT EXISTS (SELECT 1 FROM notification_subscription_sessions b
                              WHERE b.subscription_id = s.subscription_id AND b.instance_id = ?)
            """)
        .params(now(), projectId, instanceId)
        .update();
  }

  private void deactivate(String id) {
    jdbc.sql("DELETE FROM notification_subscription_sessions WHERE subscription_id = ?")
        .param(id)
        .update();
    jdbc.sql(
            "UPDATE notification_subscriptions SET active_yn = 'N', updated_at = ? WHERE"
                + " subscription_id = ?")
        .params(now(), id)
        .update();
  }

  private boolean valid(LiveSession binding) {
    HttpSession session = binding.session();
    if (session == null) return false;
    try {
      session.getCreationTime();
      int timeout = session.getMaxInactiveInterval();
      if (timeout > 0 && clock.millis() - session.getLastAccessedTime() >= timeout * 1000L)
        return false;
      Object stored =
          session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
      if (!(stored instanceof SecurityContext context)
          || context.getAuthentication() == null
          || !context.getAuthentication().isAuthenticated()) return false;
      return context.getAuthentication().getPrincipal() instanceof BintimePrincipal principal
          && binding.userId().equals(principal.userId());
    } catch (IllegalStateException invalidated) {
      return false;
    }
  }

  private String user(BintimePrincipal principal) {
    require(principal != null && principal.userId() != null, 401, "UNAUTHENTICATED");
    return principal.userId();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private <T> T locked(Supplier<T> action) {
    lifecycle.lock();
    try {
      return action.get();
    } finally {
      lifecycle.unlock();
    }
  }

  private <T> T transaction(Supplier<T> action) {
    return transactions.execute(status -> action.get());
  }
}
