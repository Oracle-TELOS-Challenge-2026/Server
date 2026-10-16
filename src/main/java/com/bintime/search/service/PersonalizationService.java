package com.bintime.search.service;

import com.bintime.search.port.IntentAiPort;
import com.bintime.search.repository.SearchRepository;
import com.bintime.user.contract.SearchUserAccess;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!api-docs")
public class PersonalizationService {
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

  public void preferencesChanged(String user) {
    tx.executeWithoutResult(
        t -> {
          db.lockUser(user);
          int n =
              db.jdbc()
                  .update(
                      "update search_preference_revision set revision=revision+1 where user_id=?",
                      user);
          if (n == 0)
            db.jdbc()
                .update(
                    "insert into search_preference_revision(user_id,revision) values(?,1)", user);
          db.enqueue("VECTOR", user, clock.millis());
        });
  }

  private long version(String user) {
    var rev =
        db.jdbc()
            .queryForList(
                "select revision from search_preference_revision where user_id=?",
                Long.class,
                user);
    return db.user(user).stream().mapToLong(i -> i.version()).sum()
        + (rev.isEmpty() ? 0 : rev.getFirst());
  }

  public void refresh(String user) {
    var snapshot =
        tx.execute(
            t -> {
              db.lockUser(user);
              db.expire(user, clock.millis());
              return new Snapshot(
                  version(user),
                  db.encode(
                      db.user(user).stream()
                          .filter(i -> i.status().equals("ACTIVE"))
                          .map(i -> i.conditions())
                          .toList()));
            });
    String input = users.explicitPreferences(user) + "\n" + snapshot.text();
    String hash = WaitlistService.hash(input);
    float[] vector = ai.embed(input);
    tx.executeWithoutResult(
        t -> {
          db.lockUser(user);
          if (version(user) != snapshot.version()) return;
          int n =
              db.jdbc()
                  .update(
                      "update search_vector set"
                          + " source_version=?,input_hash=?,model=?,dimensions=1536,vector_json=?"
                          + " where user_id=?",
                      snapshot.version(),
                      hash,
                      model,
                      db.encode(vector),
                      user);
          if (n == 0)
            db.jdbc()
                .update(
                    "insert into"
                        + " search_vector(user_id,source_version,input_hash,model,dimensions,vector_json)"
                        + " values(?,?,?,?,1536,?)",
                    user,
                    snapshot.version(),
                    hash,
                    model,
                    db.encode(vector));
        });
  }

  public float[] vector(String user) {
    if (user == null) return null;
    try {
      var data =
          db.jdbc()
              .queryForList(
                  "select vector_json from search_vector where user_id=? and model=? and"
                      + " dimensions=1536 and source_version=?",
                  String.class,
                  user,
                  model,
                  version(user));
      return data.isEmpty() ? null : db.decode(data.getFirst(), float[].class);
    } catch (Exception e) {
      return null;
    }
  }

  private record Snapshot(long version, String text) {}
}
