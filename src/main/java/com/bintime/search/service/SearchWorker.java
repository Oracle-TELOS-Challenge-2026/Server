package com.bintime.search.service;

import com.bintime.notification.contract.SearchNotificationGateway;
import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.search.dto.*;
import com.bintime.search.model.Intent;
import com.bintime.search.repository.SearchRepository;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.Slot;
import com.bintime.user.contract.SearchUserAccess;
import java.time.*;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!api-docs")
public class SearchWorker {
  private final SearchRepository db;
  private final TransactionTemplate tx;
  private final SearchStoreCatalog stores;
  private final ConditionRules rules;
  private final SearchNotificationGateway notifications;
  private final SearchUserAccess users;
  private final Clock clock;
  private final PersonalizationService vectors;

  private final SearchIndexService indexes;
  private final NotificationSimilarityService similarity;

  public SearchWorker(
      SearchRepository db,
      TransactionTemplate tx,
      SearchStoreCatalog stores,
      ConditionRules rules,
      SearchNotificationGateway notifications,
      SearchUserAccess users,
      Clock clock,
      PersonalizationService vectors,
      SearchIndexService indexes,
      NotificationSimilarityService similarity) {
    this.db = db;
    this.tx = tx;
    this.stores = stores;
    this.rules = rules;
    this.notifications = notifications;
    this.users = users;
    this.clock = clock;
    this.vectors = vectors;
    this.indexes = indexes;
    this.similarity = similarity;
  }

  public void poll() {
    var ready =
        db.jdbc()
            .queryForList(
                "select id from search_work where (status='QUEUED' or status='RUNNING') and"
                    + " available_at<=? order by available_at fetch first 20 rows only",
                String.class,
                clock.millis());
    for (String id : ready) run(id);
  }

  public void run(String id) {
    String owner = UUID.randomUUID().toString();
    int claimed =
        db.jdbc()
            .update(
                "update search_work set"
                    + " status='RUNNING',lease_owner=?,available_at=?,attempts=attempts+1 where"
                    + " id=? and (status='QUEUED' or status='RUNNING') and available_at<=?",
                owner,
                clock.millis() + 900000,
                id,
                clock.millis());
    if (claimed == 0) return;
    var row = db.jdbc().queryForMap("select kind,payload,attempts from search_work where id=?", id);
    String kind = (String) row.get("KIND");
    String payload =
        db.jdbc().queryForObject("select payload from search_work where id=?", String.class, id);
    try {
      switch (kind) {
        case "SLOT" -> slot(db.decode(payload, CreateMatchJobRequest.class), id, owner);
        case "INTENT" -> intent(payload, id, owner);
        case "NOTIFY" -> notifyUser(db.decode(payload, Delivery.class));
        case "PERSONALIZE" -> personalizedUser(payload, id, owner);
        case "VECTOR" -> vectors.refresh(payload);
        case "INDEX" -> indexes.sync(payload);
        case "STORE_VECTOR" -> indexes.embed(payload);
        default -> throw new IllegalStateException("Unknown work kind");
      }
      db.jdbc()
          .update(
              "update search_work set status='SUCCEEDED',last_error=null where id=? and"
                  + " lease_owner=?",
              id,
              owner);
    } catch (Exception e) {
      int attempt = ((Number) row.get("ATTEMPTS")).intValue();
      boolean retryable = !(e instanceof SearchFailure failure) || failure.retryable();
      boolean busy =
          e instanceof EmbeddingFailure failure
              && Set.of("USER_VECTOR_GENERATION_BUSY", "MATCH_VECTORS_PENDING")
                  .contains(failure.code);
      String status = !busy && (!retryable || attempt >= 5) ? "FAILED" : "QUEUED";
      db.jdbc()
          .update(
              "update search_work set status=?,available_at=?,last_error=?,attempts=? where id=?"
                  + " and lease_owner=?",
              status,
              clock.millis() + (busy ? 1000 : Math.min(300000, 1000L * (1L << attempt))),
              e instanceof SearchFailure failure ? failure.code : e.getClass().getSimpleName(),
              busy ? attempt - 1 : attempt,
              id,
              owner);
      LoggerFactory.getLogger(getClass())
          .warn(
              "Search work {} kind {} failed (attempt {}): {}",
              id,
              kind,
              attempt,
              e.getClass().getSimpleName());
    }
  }

  private void renew(String id, String owner) {
    if (db.jdbc()
            .update(
                "update search_work set available_at=? where id=? and lease_owner=? and"
                    + " status='RUNNING'",
                clock.millis() + 900000,
                id,
                owner)
        == 0) throw new IllegalStateException("Lease lost");
  }

  private void slot(CreateMatchJobRequest event, String work, String owner) {
    var slots = stores.revalidate(List.of(event.slotId()));
    if (!slots.isEmpty()
        && Objects.equals(slots.getFirst().publicationVersion(), event.publicationVersion())) {
      Slot s = slots.getFirst();
      String after = "";
      while (true) {
        renew(work, owner);
        var batch = db.activeUsers(after, 100);
        if (batch.isEmpty()) break;
        for (String user : batch) {
          tx.executeWithoutResult(
              t -> {
                db.lockUser(user);
                db.expire(user, clock.millis());
                var matches =
                    db.user(user).stream()
                        .filter(
                            i ->
                                db.consented(i)
                                    && rules.matches(i.conditions(), s, clock.instant()))
                        .toList();
                save(matches, s);
              });
        }
        after = batch.getLast();
      }
      if ("VACANCY".equals(s.source())) {
        after = "";
        while (true) {
          renew(work, owner);
          var candidates = users.personalizedCandidates(after, 100);
          if (candidates.isEmpty()) break;
          for (String user : candidates) enqueuePersonalized(user, s);
          after = candidates.getLast();
        }
      }
    }
  }

  private void enqueuePersonalized(String user, Slot slot) {
    if (!users.personalizedNotificationsEnabled(user) || !users.notificationsEnabled(user)) return;
    db.enqueue(
        "NOTIFY",
        db.encode(
            new Delivery(user, slot.id(), slot.publicationVersion(), List.of(), "PERSONALIZED")),
        clock.millis());
  }

  private void personalizedUser(String user, String work, String owner) {
    if (!users.personalizedNotificationsEnabled(user) || !users.notificationsEnabled(user)) return;
    String cursor = null;
    do {
      renew(work, owner);
      var page = stores.export(cursor, 100);
      for (var store : page.items()) {
        for (Slot slot : store.slots()) {
          if ("VACANCY".equals(slot.source())
              && slot.startAt().toInstant().isAfter(clock.instant()))
            enqueuePersonalized(user, slot);
        }
      }
      cursor = page.nextCursor();
    } while (cursor != null);
  }

  private void intent(String payload, String work, String owner) {
    String[] parts = payload.split(":");
    var found = db.find(parts[0]);
    if (found.isEmpty()) return;
    Intent original = found.get();
    long version = Long.parseLong(parts[1]);
    if (original.version() != version || !db.consented(original)) return;
    String cursor = null;
    do {
      renew(work, owner);
      var page = stores.available(original.conditions(), cursor, 100);
      var valid =
          page.items().isEmpty()
              ? List.<Slot>of()
              : stores.revalidate(page.items().stream().map(Slot::id).toList());
      tx.executeWithoutResult(
          t -> {
            db.lockUser(original.userId());
            db.expire(original.userId(), clock.millis());
            var current = db.find(original.id()).orElseThrow();
            if (current.version() != version || !db.consented(current)) return;
            for (Slot s : valid)
              if (rules.matches(current.conditions(), s, clock.instant())) {
                var matches =
                    db.user(current.userId()).stream()
                        .filter(
                            i ->
                                db.consented(i)
                                    && rules.matches(i.conditions(), s, clock.instant()))
                        .toList();
                save(matches, s);
              }
          });
      cursor = page.nextCursor();
    } while (cursor != null);
  }

  private void save(List<Intent> matches, Slot s) {
    for (Intent i : matches) {
      int n =
          db.jdbc()
              .queryForObject(
                  "select count(*) from search_offer where intent_id=? and intent_version=? and"
                      + " slot_id=?",
                  Integer.class,
                  i.id(),
                  i.version(),
                  s.id());
      if (n == 0)
        db.jdbc()
            .update(
                "insert into search_offer(intent_id,intent_version,slot_id,publication_version)"
                    + " values(?,?,?,?)",
                i.id(),
                i.version(),
                s.id(),
                s.publicationVersion());
      else if (s.publicationVersion() != null)
        // 같은 빈자리의 재발행도 현재 제안에 반영한다. 늦게 도착한 이전 발행은 되돌리지 않는다.
        db.jdbc()
            .update(
                "update search_offer set publication_version=? where intent_id=? and"
                    + " intent_version=? and slot_id=? and (publication_version is null or"
                    + " publication_version<?)",
                s.publicationVersion(),
                i.id(),
                i.version(),
                s.id(),
                s.publicationVersion());
    }
    if (!matches.isEmpty())
      db.enqueue(
          "NOTIFY",
          db.encode(
              new Delivery(
                  matches.getFirst().userId(),
                  s.id(),
                  s.publicationVersion(),
                  matches.stream()
                      .map(i -> new DispatchNotificationRequest.MatcheItem(i.id(), i.version()))
                      .toList())),
          clock.millis());
  }

  private void notifyUser(Delivery delivery) {
    if (!users.notificationsEnabled(delivery.userId())) return;
    var valid = stores.revalidate(List.of(delivery.slotId()));
    if (valid.isEmpty()) return;
    Slot s = valid.getFirst();
    if (!Objects.equals(s.publicationVersion(), delivery.publicationVersion())) return;
    if ("PERSONALIZED".equals(delivery.audience())) {
      if (!"VACANCY".equals(s.source())
          || !users.personalizedNotificationsEnabled(delivery.userId())) return;
      if (!similarity.matches(delivery.userId(), s)) return;
      notifications.dispatch(
          new DispatchNotificationRequest(
              "match_" + WaitlistService.hash(delivery.userId() + ":" + s.id()),
              delivery.userId(),
              "VACANCY_MATCHED",
              s.id(),
              s.publicationVersion(),
              List.of(),
              s.startAt(),
              "PERSONALIZED"));
      return;
    }
    if (!"WAITLIST".equals(delivery.audience()))
      throw SearchFailure.bad("INVALID_NOTIFICATION_AUDIENCE");
    var matches =
        tx.execute(
            t -> {
              db.lockUser(delivery.userId());
              db.expire(delivery.userId(), clock.millis());
              return db.user(delivery.userId()).stream()
                  .filter(
                      i ->
                          delivery.matches().stream()
                              .anyMatch(
                                  m ->
                                      m.waitlistId().equals(i.id())
                                          && m.waitlistVersion() == i.version()))
                  .filter(i -> db.consented(i) && rules.matches(i.conditions(), s, clock.instant()))
                  .map(i -> new DispatchNotificationRequest.MatcheItem(i.id(), i.version()))
                  .toList();
            });
    if (matches.isEmpty()) return;
    if (!similarity.matches(delivery.userId(), s)) return;
    // 외부 전달은 DB 잠금 밖에서 한다. 수신자가 버전/동의와 user+slot 중복을 최종 확인한다.
    notifications.dispatch(
        new DispatchNotificationRequest(
            "match_" + WaitlistService.hash(delivery.userId() + ":" + s.id()),
            delivery.userId(),
            "VACANCY_MATCHED",
            s.id(),
            s.publicationVersion(),
            matches,
            s.startAt()));
  }

  public void expire() {
    String after = "";
    while (true) {
      var users = db.activeUsers(after, 100);
      if (users.isEmpty()) return;
      for (String user : users)
        tx.executeWithoutResult(
            t -> {
              db.lockUser(user);
              db.expire(user, clock.millis());
            });
      after = users.getLast();
    }
  }

  public record Delivery(
      String userId,
      String slotId,
      Long publicationVersion,
      List<DispatchNotificationRequest.MatcheItem> matches,
      String audience) {
    public Delivery {
      audience = audience == null ? "WAITLIST" : audience;
    }

    public Delivery(
        String userId,
        String slotId,
        Long publicationVersion,
        List<DispatchNotificationRequest.MatcheItem> matches) {
      this(userId, slotId, publicationVersion, matches, "WAITLIST");
    }
  }
}
