package com.bintime.notification.repository;

import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.dto.NotificationResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("!api-docs")
public class NotificationRepository {
  private static final Logger log = LoggerFactory.getLogger(NotificationRepository.class);
  private final JdbcTemplate jdbc;
  private final JsonMapper mapper;

  public NotificationRepository(JdbcTemplate jdbc, JsonMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  public boolean lockUser(String user) {
    return !jdbc.queryForList(
            "select user_id from user_accounts where user_id=? for update", String.class, user)
        .isEmpty();
  }

  public String duplicate(String user, String slot) {
    return jdbc
        .queryForList(
            "select notification_id from notification_dedup where user_id=? and slot_id=?",
            String.class,
            user,
            slot)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public Alert find(String user, String id) {
    return jdbc
        .query(
            "select * from notification_alerts where user_id=? and notification_id=?",
            this::alert,
            user,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Alert> list(String user, boolean unreadOnly, Alert before, int limit) {
    String sql = "select * from notification_alerts where user_id=?";
    var params = new ArrayList<Object>();
    params.add(user);
    if (unreadOnly) sql += " and read_at is null";
    if (before != null) {
      sql += " and (created_at<? or (created_at=? and notification_id<?))";
      params.add(before.notification().createdAt());
      params.add(before.notification().createdAt());
      params.add(before.notification().id());
    }
    sql += " order by created_at desc,notification_id desc fetch first " + limit + " rows only";
    return jdbc.query(sql, this::alert, params.toArray());
  }

  public int unread(String user) {
    return jdbc.queryForObject(
        "select count(*) from notification_alerts where user_id=? and read_at is null",
        Integer.class,
        user);
  }

  public void insert(
      String user,
      DispatchNotificationRequest request,
      NotificationResponse alert,
      OffsetDateTime expiresAt,
      List<String> subscriptions) {
    jdbc.update(
        """
        insert into notification_alerts
          (notification_id,user_id,waitlist_id,slot_id,publication_version,store_id,title,body_text,
           expires_at,push_status,push_attempts,created_at,snapshot_json,request_json)
        values (?,?,?,?,?,?,?,?,?,'PENDING',0,?,?,?)
        """,
        alert.id(),
        user,
        request.matches().isEmpty() ? null : request.matches().getFirst().waitlistId(),
        request.slotId(),
        request.publicationVersion(),
        alert.target().storeId(),
        alert.title(),
        alert.body(),
        expiresAt,
        alert.createdAt(),
        mapper.writeValueAsString(alert),
        mapper.writeValueAsString(request));
    appendPushHistory(push(user, alert), null, 0, "QUEUED", null, alert.createdAt());
    jdbc.update(
        "insert into notification_dedup(user_id,slot_id,notification_id) values (?,?,?)",
        user,
        request.slotId(),
        alert.id());
    for (String subscription : subscriptions) {
      jdbc.update(
          """
          insert into notification_deliveries
            (delivery_id,notification_id,subscription_id,status,attempts,available_at)
          values (?,?,?,'QUEUED',0,0)
          """,
          UUID.randomUUID().toString(),
          alert.id(),
          subscription);
    }
  }

  public boolean read(String user, String id, OffsetDateTime now) {
    return jdbc.update(
            """
            update notification_alerts set read_at=?
            where user_id=? and notification_id=? and read_at is null
            """,
            now,
            user,
            id)
        == 1;
  }

  public void event(String user, String type, Object payload, OffsetDateTime now) {
    long sequence = latestSequence(user) + 1;
    jdbc.update(
        """
        insert into notification_events(user_id,sequence_no,event_id,event_type,payload_json,created_at)
        values (?,?,?,?,?,?)
        """,
        user,
        sequence,
        "evt_" + UUID.randomUUID(),
        type,
        mapper.writeValueAsString(payload),
        now);
  }

  public long latestSequence(String user) {
    return jdbc.queryForObject(
        "select coalesce(max(sequence_no),0) from notification_events where user_id=?",
        Long.class,
        user);
  }

  public Event event(String user, String id) {
    return jdbc
        .query(
            "select * from notification_events where user_id=? and event_id=?",
            this::event,
            user,
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public List<Event> events(String user, long after, OffsetDateTime since) {
    return jdbc.query(
        """
        select * from notification_events where user_id=? and sequence_no>? and created_at>=?
        order by sequence_no fetch first 100 rows only
        """,
        this::event,
        user,
        after,
        since);
  }

  public List<String> due(long now) {
    return jdbc.queryForList(
        """
        select delivery_id from notification_deliveries
        where status in ('QUEUED','RUNNING') and available_at<=?
        order by available_at,delivery_id fetch first 50 rows only
        """,
        String.class,
        now);
  }

  public Delivery claim(String id, long now) {
    String owner = UUID.randomUUID().toString();
    if (jdbc.update(
            """
            update notification_deliveries set status='RUNNING',attempts=attempts+1,
              available_at=?,lease_owner=?
            where delivery_id=? and status in ('QUEUED','RUNNING') and available_at<=?
            """,
            now + 180_000,
            owner,
            id,
            now)
        == 0) return null;
    var delivery =
        jdbc.queryForObject(
            """
            select d.*,a.user_id from notification_deliveries d
            join notification_alerts a on a.notification_id=d.notification_id where d.delivery_id=?
            """,
            (rs, row) ->
                new Delivery(
                    id,
                    rs.getString("notification_id"),
                    rs.getString("user_id"),
                    rs.getString("subscription_id"),
                    rs.getInt("attempts"),
                    owner),
            id);
    appendPushHistory(
        push(delivery),
        delivery.subscriptionId(),
        delivery.attempts(),
        "RUNNING",
        null,
        OffsetDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    return delivery;
  }

  /**
   * Called under the user's database lock; pacing defers a delivery without consuming an attempt.
   */
  public long reserveSendWindow(String user, long now, long minimumIntervalMillis) {
    var rows =
        jdbc.queryForList(
            "select last_sent_at from notification_delivery_clock where user_id=?",
            Long.class,
            user);
    if (!rows.isEmpty() && rows.getFirst() + minimumIntervalMillis > now) {
      return rows.getFirst() + minimumIntervalMillis;
    }
    if (rows.isEmpty()) {
      jdbc.update(
          "insert into notification_delivery_clock(user_id,last_sent_at) values (?,?)", user, now);
    } else {
      jdbc.update(
          "update notification_delivery_clock set last_sent_at=? where user_id=?", now, user);
    }
    return now;
  }

  public void finish(
      Delivery delivery,
      String status,
      String code,
      long availableAt,
      boolean consumedAttempt,
      OffsetDateTime now) {
    if (jdbc.update(
            """
            update notification_deliveries set status=?,error_code=?,available_at=?,lease_owner=null,
              attempts=attempts-?,accepted_at=?
            where delivery_id=? and status='RUNNING' and lease_owner=?
            """,
            status,
            code,
            availableAt,
            consumedAttempt ? 0 : 1,
            "ACCEPTED".equals(status) ? now : null,
            delivery.id(),
            delivery.leaseOwner())
        == 0) return;
    var statuses =
        jdbc.queryForList(
            "select status from notification_deliveries where notification_id=?",
            String.class,
            delivery.notificationId());
    boolean pending = statuses.stream().anyMatch(s -> s.equals("QUEUED") || s.equals("RUNNING"));
    long accepted = statuses.stream().filter("ACCEPTED"::equals).count();
    String aggregate =
        pending
            ? "PENDING"
            : accepted == statuses.size()
                ? "SENT"
                : accepted > 0 ? "PARTIAL" : statuses.contains("FAILED") ? "FAILED" : "EXPIRED";
    // Oracle needs an explicit type when the nullable acceptance timestamp is used in COALESCE.
    jdbc.update(
        """
        update notification_alerts set push_status=?,push_attempts=push_attempts+?,
          push_attempted_at=?,push_accepted_at=coalesce(push_accepted_at,cast(? as timestamp with time zone)),push_error_code=?
        where notification_id=?
        """,
        aggregate,
        consumedAttempt ? 1 : 0,
        now,
        "ACCEPTED".equals(status) ? now : null,
        code,
        delivery.notificationId());
    try {
      appendPushHistory(
          push(delivery), delivery.subscriptionId(), delivery.attempts(), status, code, now);
    } catch (RuntimeException unavailable) {
      // 결과 이력만 실패하면 기존 발송 상태를 유지해 이미 접수된 푸시를 재발송하지 않는다.
      log.warn(
          "Notification push result history unavailable; notification={}",
          delivery.notificationId());
    }
  }

  private Push push(Delivery delivery) {
    return push(
        delivery.userId(), find(delivery.userId(), delivery.notificationId()).notification());
  }

  private static Push push(String user, NotificationResponse notification) {
    return new Push(
        notification.type(),
        notification.id(),
        user,
        notification.title(),
        notification.body(),
        notification.action().path());
  }

  /** 생성 기록은 원본 알림/메시지와 같은 트랜잭션에서 저장한다. */
  public void appendPushHistory(
      Push push, String subscription, int attempt, String status, String code, OffsetDateTime now) {
    jdbc.update(
        """
        insert into notification_push_history
          (history_id,notification_type,notification_id,user_id,subscription_id,attempt_no,
           status,error_code,title,body_text,action_path,created_at)
        values (?,?,?,?,?,?,?,?,?,?,?,?)
        """,
        UUID.randomUUID().toString(),
        push.type(),
        push.id(),
        push.userId(),
        subscription,
        attempt,
        status,
        code,
        push.title(),
        push.body(),
        push.path(),
        now);
  }

  /** AFTER_COMMIT 콜백의 남아 있는 트랜잭션에 이력 쓰기가 묶이지 않게 한다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordPushHistory(
      Push push, String subscription, int attempt, String status, String code, OffsetDateTime now) {
    appendPushHistory(push, subscription, attempt, status, code, now);
  }

  public record Push(
      String type, String id, String userId, String title, String body, String path) {}

  private Alert alert(ResultSet rs, int row) throws SQLException {
    String json = rs.getString("snapshot_json");
    var created = rs.getObject("created_at", OffsetDateTime.class);
    var read = rs.getObject("read_at", OffsetDateTime.class);
    NotificationResponse value;
    if (json == null) {
      // Historical alerts remain readable after the legacy waitlist foreign key is removed.
      value =
          new NotificationResponse(
              rs.getString("notification_id"),
              "VACANCY_MATCHED",
              rs.getString("title"),
              rs.getString("body_text"),
              new NotificationResponse.Target(
                  rs.getString("store_id"),
                  rs.getString("slot_id"),
                  rs.getObject("publication_version", Long.class),
                  rs.getString("waitlist_id") == null
                      ? List.of()
                      : List.of(rs.getString("waitlist_id"))),
              new NotificationResponse.Action("OPEN_SLOT", "/customer/waitlist"),
              "UNKNOWN",
              read,
              created);
    } else {
      var saved = mapper.readValue(json, NotificationResponse.class);
      value =
          new NotificationResponse(
              saved.id(),
              saved.type(),
              saved.title(),
              saved.body(),
              saved.target(),
              saved.action(),
              saved.availability(),
              read,
              created);
    }
    String request = rs.getString("request_json");
    return new Alert(
        value,
        request == null ? null : mapper.readValue(request, DispatchNotificationRequest.class),
        rs.getObject("expires_at", OffsetDateTime.class));
  }

  private Event event(ResultSet rs, int row) throws SQLException {
    return new Event(
        rs.getLong("sequence_no"),
        rs.getString("event_id"),
        rs.getString("event_type"),
        rs.getString("payload_json"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  public record Alert(
      NotificationResponse notification,
      DispatchNotificationRequest request,
      OffsetDateTime expiresAt) {}

  public record Event(
      long sequence, String id, String type, String json, OffsetDateTime createdAt) {}

  public record Delivery(
      String id,
      String notificationId,
      String userId,
      String subscriptionId,
      int attempts,
      String leaseOwner) {}
}
