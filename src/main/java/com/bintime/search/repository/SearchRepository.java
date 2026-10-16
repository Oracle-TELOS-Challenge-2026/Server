package com.bintime.search.repository;

import com.bintime.search.dto.*;
import com.bintime.search.model.Intent;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
@Profile("!api-docs")
public class SearchRepository {
  private final JdbcTemplate jdbc;

  public JdbcTemplate jdbc() {
    return jdbc;
  }

  private final JsonMapper json;

  public SearchRepository(JdbcTemplate jdbc, JsonMapper json) {
    this.jdbc = jdbc;
    this.json = json;
  }

  public String encode(Object o) {
    return json.writeValueAsString(o);
  }

  public <T> T decode(String s, Class<T> type) {
    return json.readValue(s, type);
  }

  // 잠금 행을 미리 만들어 첫 등록도 같은 사용자 잠금에 참여하게 한다.
  public void lockUser(String user) {
    jdbc.queryForObject(
        "select bucket_id from search_user_lock where bucket_id=? for update",
        Integer.class,
        Math.floorMod(user.hashCode(), 256));
  }

  public List<Intent> user(String user) {
    return jdbc.query(
        "select * from search_intent where user_id=? order by id",
        (r, n) ->
            new Intent(
                r.getString("id"),
                r.getString("user_id"),
                r.getString("source"),
                decode(r.getString("conditions_json"), WaitlistConditions.class),
                r.getString("status"),
                r.getLong("resource_version"),
                r.getLong("created_at"),
                r.getLong("consent_at"),
                r.getString("reservation_id")),
        user);
  }

  public List<String> activeUsers(String after, int limit) {
    return jdbc.queryForList(
        "select distinct user_id from search_intent where status='ACTIVE' and (? is null or"
            + " user_id>?) order by user_id fetch first ? rows only",
        String.class,
        after,
        after,
        limit);
  }

  public Optional<Intent> find(String id) {
    var users = jdbc.queryForList("select user_id from search_intent where id=?", String.class, id);
    return users.isEmpty()
        ? Optional.empty()
        : user(users.getFirst()).stream().filter(i -> i.id().equals(id)).findFirst();
  }

  public void insert(Intent i) {
    jdbc.update(
        "insert into"
            + " search_intent(id,user_id,source,conditions_json,status,resource_version,created_at,consent_at,window_end)"
            + " values(?,?,?,?,?,?,?,?,?)",
        i.id(),
        i.userId(),
        i.source(),
        encode(i.conditions()),
        i.status(),
        i.version(),
        i.createdAt(),
        i.consentAt(),
        i.conditions().windowEnd().toInstant().toEpochMilli());
    consent(i);
  }

  public void update(Intent i) {
    jdbc.update(
        "update search_intent set conditions_json=?,"
            + " status=?,resource_version=?,consent_at=?,window_end=?,reservation_id=? where id=?",
        encode(i.conditions()),
        i.status(),
        i.version(),
        i.consentAt(),
        i.conditions().windowEnd().toInstant().toEpochMilli(),
        i.reservationId(),
        i.id());
  }

  public void consent(Intent i) {
    jdbc.update(
        "insert into search_consent(intent_id,resource_version,consent_at,conditions_json)"
            + " values(?,?,?,?)",
        i.id(),
        i.version(),
        i.consentAt(),
        encode(i.conditions()));
  }

  public void expire(String user, long now) {
    int changed =
        jdbc.update(
            "update search_intent set status='EXPIRED',resource_version=resource_version+1 where"
                + " user_id=? and status='ACTIVE' and window_end<=?",
            user,
            now);
    if (changed > 0) enqueue("VECTOR", user, now);
  }

  public String replay(String user, String operation, String key, String hash, long now) {
    var rows =
        jdbc.queryForList(
            "select request_hash,response_json,expires_at from search_idempotency where user_id=?"
                + " and operation=? and idem_key=?",
            user,
            operation,
            key);
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (((Number) row.get("EXPIRES_AT")).longValue() <= now) {
      jdbc.update(
          "delete from search_idempotency where user_id=? and operation=? and idem_key=?",
          user,
          operation,
          key);
      return null;
    }
    if (!row.get("REQUEST_HASH").equals(hash))
      throw com.bintime.search.service.SearchFailure.conflict("IDEMPOTENCY_CONFLICT");
    return jdbc.queryForObject(
        "select response_json from search_idempotency where user_id=? and operation=? and"
            + " idem_key=?",
        String.class,
        user,
        operation,
        key);
  }

  public void remember(
      String user, String operation, String key, String hash, Object result, long now) {
    jdbc.update(
        "insert into"
            + " search_idempotency(user_id,operation,idem_key,request_hash,response_json,expires_at)"
            + " values(?,?,?,?,?,?)",
        user,
        operation,
        key,
        hash,
        encode(result),
        now + 86400000);
  }

  public void enqueue(String kind, String payload, long now) {
    jdbc.update(
        "insert into search_work(id,kind,payload,status,attempts,available_at)"
            + " values(?,?,?,'QUEUED',0,?)",
        UUID.randomUUID().toString(),
        kind,
        payload,
        now);
  }
}
