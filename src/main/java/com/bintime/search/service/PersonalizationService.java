package com.bintime.search.service;

import com.bintime.search.model.EmbeddingVector;
import com.bintime.search.port.IntentAiPort;
import com.bintime.search.repository.SearchRepository;
import com.bintime.user.contract.SearchUserAccess;
import com.bintime.user.contract.UserPreferencesChanged;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

@Service
@Profile("!api-docs")
public class PersonalizationService {
  private static final String INPUT_FORMAT = "preferences-json-v1";
  private static final int MAX_INPUT_BYTES = 8000;
  private final SearchRepository db;
  private final IntentAiPort ai;
  private final SearchUserAccess users;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final String model;

  public PersonalizationService(
      SearchRepository db,
      IntentAiPort ai,
      SearchUserAccess users,
      TransactionTemplate tx,
      Clock clock,
      @Value("${search.ai.embedding-model:text-embedding-3-small}") String model) {
    this.db = db;
    this.ai = ai;
    this.users = users;
    this.tx = tx;
    this.clock = clock;
    this.model = model;
  }

  @EventListener
  public void preferencesChanged(UserPreferencesChanged event) {
    preferencesChanged(event.userId());
  }

  public void preferencesChanged(String user) {
    tx.executeWithoutResult(
        t -> {
          db.lockUser(user);
          int n =
              db.jdbc()
                  .update(
                      "update search_user_vector set revision=revision+1 where user_id=?", user);
          if (n == 0)
            db.jdbc().update("insert into search_user_vector(user_id,revision) values(?,1)", user);
          db.enqueue("VECTOR", user, clock.millis());
        });
  }

  /** 운영 명령과 다른 내부 유스케이스가 호출할 최초 생성/재생성 진입점. */
  public void requestRefresh(String user) {
    requestRefresh(user, false);
  }

  public void requestMatchingRefresh(String user) {
    requestRefresh(user, true);
  }

  private void requestRefresh(String user, boolean matching) {
    users.explicitPreferences(user);
    tx.executeWithoutResult(
        t -> {
          db.lockUser(user);
          // 같은 사용자에 대한 벡터 준비 대기는 기존 작업을 재사용한다.
          if (db.jdbc()
              .queryForList(
                  "select payload from search_work where kind='VECTOR' and status in"
                      + " ('QUEUED','RUNNING')",
                  String.class)
              .contains(user)) return;
          // 생성 작업이 최종 실패했다면 알림 재시도로 유료 호출을 무한 재생성하지 않는다.
          if (matching
              && db.jdbc()
                  .queryForList(
                      "select payload from search_work where kind='VECTOR' and status='FAILED'",
                      String.class)
                  .contains(user)) throw new EmbeddingFailure("USER_MATCH_VECTOR_FAILED", false);
          db.enqueue("VECTOR", user, clock.millis());
        });
  }

  public boolean hasPreferences(String user) {
    return !empty(users.explicitPreferences(user));
  }

  private long version(String user) {
    var rows =
        db.jdbc()
            .queryForList(
                "select revision from search_user_vector where user_id=?", Long.class, user);
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  private Snapshot snapshot(String user) {
    String input = users.explicitPreferences(user);
    return new Snapshot(version(user), input, inputHash(input));
  }

  private String inputHash(String input) {
    return WaitlistService.hash(
        INPUT_FORMAT
            + ":"
            + model
            + ":"
            + EmbeddingVector.DIMENSIONS
            + ":"
            + Objects.toString(input, "null"));
  }

  public void refresh(String user) {
    Generation generation =
        tx.execute(
            t -> {
              db.lockUser(user);
              if (db.jdbc()
                      .queryForObject(
                          "select count(*) from search_user_vector where user_id=?",
                          Integer.class,
                          user)
                  == 0)
                db.jdbc()
                    .update("insert into search_user_vector(user_id,revision) values(?,0)", user);
              var snapshot = snapshot(user);
              if (empty(snapshot.input())) {
                db.jdbc()
                    .update(
                        "update search_user_vector set"
                            + " source_version=null,input_hash=null,model=null,dimensions=null,vector_json=null"
                            + " where user_id=?",
                        user);
                return null;
              }
              if (stored(user, snapshot.hash()) != null) {
                db.jdbc()
                    .update(
                        "update search_user_vector set source_version=? where user_id=?",
                        snapshot.version(),
                        user);
                return null;
              }
              var leases =
                  db.jdbc()
                      .queryForList(
                          "select lease_until from search_user_vector where user_id=? and"
                              + " lease_owner is not null",
                          Long.class,
                          user);
              if (!leases.isEmpty() && leases.getFirst() > clock.millis())
                throw new EmbeddingFailure("USER_VECTOR_GENERATION_BUSY", true);
              String owner = UUID.randomUUID().toString();
              db.jdbc()
                  .update(
                      "update search_user_vector set lease_owner=?,lease_until=? where user_id=?",
                      owner,
                      clock.millis() + 30000,
                      user);
              return new Generation(snapshot, owner);
            });
    if (generation == null) return;
    var captured = generation.snapshot();
    try {
      // API 호출은 잠금 밖에서 한다. 별도 DB lease는 다른 worker의 동시 호출만 막는다.
      float[] vector = EmbeddingVector.validate(ai.embed(captured.input()));
      tx.executeWithoutResult(
          t -> {
            db.lockUser(user);
            var owners =
                db.jdbc()
                    .queryForList(
                        "select lease_owner from search_user_vector where user_id=?",
                        String.class,
                        user);
            if (owners.isEmpty() || !Objects.equals(owners.getFirst(), generation.owner())) return;
            var latest = snapshot(user);
            if (latest.version() != captured.version() || !latest.hash().equals(captured.hash())) {
              db.enqueue("VECTOR", user, clock.millis());
              return;
            }
            db.jdbc()
                .update(
                    "update search_user_vector set"
                        + " source_version=?,input_hash=?,model=?,dimensions=?,vector_json=? where"
                        + " user_id=?",
                    captured.version(),
                    captured.hash(),
                    model,
                    EmbeddingVector.DIMENSIONS,
                    db.encode(vector),
                    user);
          });
    } finally {
      tx.executeWithoutResult(
          t -> {
            db.lockUser(user);
            db.jdbc()
                .update(
                    "update search_user_vector set lease_owner=null,lease_until=null where"
                        + " user_id=? and lease_owner=?",
                    user,
                    generation.owner());
          });
    }
  }

  public float[] vector(String user) {
    if (user == null) return null;
    try {
      return tx.execute(
          t -> {
            db.lockUser(user);
            var s = snapshot(user);
            if (empty(s.input())) return null;
            var rows =
                db.jdbc()
                    .queryForList(
                        "select source_version from search_user_vector where user_id=? and"
                            + " vector_json is not null",
                        Long.class,
                        user);
            if (rows.isEmpty() || rows.getFirst() != s.version()) return null;
            return stored(user, s.hash());
          });
    } catch (Exception e) {
      return null;
    }
  }

  private float[] stored(String user, String hash) {
    var rows =
        db.jdbc()
            .queryForList(
                "select vector_json from search_user_vector where user_id=? and input_hash=? and"
                    + " model=? and dimensions=?",
                String.class,
                user,
                hash,
                model,
                EmbeddingVector.DIMENSIONS);
    if (rows.isEmpty()) return null;
    try {
      return EmbeddingVector.validate(db.decode(rows.getFirst(), float[].class));
    } catch (Exception e) {
      return null;
    }
  }

  private boolean empty(String input) {
    if (input == null || input.isBlank()) return true;
    if (input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES)
      throw SearchFailure.bad("PREFERENCES_INPUT_TOO_LARGE");
    JsonNode value;
    try {
      value = db.decode(input, JsonNode.class);
    } catch (Exception e) {
      throw SearchFailure.bad("INVALID_PREFERENCES_JSON");
    }
    return value == null
        || value.isNull()
        || value.isContainer() && value.isEmpty()
        || value.isString() && value.asText().isBlank();
  }

  private record Generation(Snapshot snapshot, String owner) {}

  private record Snapshot(long version, String input, String hash) {}
}
