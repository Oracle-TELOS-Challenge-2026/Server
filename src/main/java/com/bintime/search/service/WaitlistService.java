package com.bintime.search.service;

import com.bintime.common.dto.CursorPage;
import com.bintime.search.dto.*;
import com.bintime.search.model.Intent;
import com.bintime.search.repository.SearchRepository;
import com.bintime.store.contract.SearchStoreCatalog;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!api-docs")
public class WaitlistService {
  private final SearchRepository db;
  private final ConditionRules rules;
  private final SearchStoreCatalog stores;
  private final Clock clock;
  private final TransactionTemplate tx;

  public WaitlistService(
      SearchRepository db,
      ConditionRules rules,
      SearchStoreCatalog stores,
      Clock clock,
      TransactionTemplate tx) {
    this.db = db;
    this.rules = rules;
    this.stores = stores;
    this.clock = clock;
    this.tx = tx;
  }

  public WaitlistResponse create(String user, String key, CreateWaitlistRequest r) {
    key(key);
    if (r == null || !Boolean.TRUE.equals(r.notificationConsent()))
      throw SearchFailure.bad("NOTIFICATION_CONSENT_REQUIRED");
    if (!Set.of("STORE_DETAIL", "RESERVATION_FAILED", "NATURAL_LANGUAGE", "MANUAL")
        .contains(Objects.toString(r.source(), ""))) throw SearchFailure.bad("INVALID_SOURCE");
    var c = rules.normalize(r.conditions(), Instant.MIN);
    String hash = hash(db.encode(new CreateWaitlistRequest(r.source(), c, true)));
    var replay =
        tx.execute(
            t -> {
              db.lockUser(user);
              return db.replay(user, "CREATE", key, hash, clock.millis());
            });
    if (replay != null) return db.decode(replay, WaitlistResponse.class);
    rules.normalize(c, clock.instant());
    stores.validate(c);
    return tx.execute(
        t -> {
          db.lockUser(user);
          long now = clock.millis();
          String again = db.replay(user, "CREATE", key, hash, now);
          if (again != null) return db.decode(again, WaitlistResponse.class);
          db.expire(user, now);
          var active = db.user(user).stream().filter(i -> i.status().equals("ACTIVE")).toList();
          duplicate(active, c, null);
          if (active.size() >= 20) throw SearchFailure.conflict("ACTIVE_WAITLIST_LIMIT");
          Intent i =
              new Intent(
                  UUID.randomUUID().toString(), user, r.source(), c, "ACTIVE", 1, now, now, null);
          db.insert(i);
          followup(i);
          var result = response(i, List.of());
          db.remember(user, "CREATE", key, hash, result, now);
          return result;
        });
  }

  public WaitlistResponse update(String user, String id, UpdateWaitlistRequest r) {
    if (r == null || !Boolean.TRUE.equals(r.notificationConsent()))
      throw SearchFailure.bad("NOTIFICATION_CONSENT_REQUIRED");
    var c = rules.normalize(r.conditions(), clock.instant());
    stores.validate(c);
    return tx.execute(
        t -> {
          db.lockUser(user);
          db.expire(user, clock.millis());
          var i = owned(user, id);
          version(i, r.expectedVersion());
          active(i);
          duplicate(
              db.user(user).stream().filter(x -> x.status().equals("ACTIVE")).toList(), c, id);
          var changed =
              new Intent(
                  i.id(),
                  user,
                  i.source(),
                  c,
                  "ACTIVE",
                  i.version() + 1,
                  i.createdAt(),
                  clock.millis(),
                  null);
          db.update(changed);
          db.consent(changed);
          followup(changed);
          return response(changed, List.of());
        });
  }

  public WaitlistResponse cancel(String user, String id, String key, CancelWaitlistRequest r) {
    key(key);
    if (r == null || r.expectedVersion() == null) throw SearchFailure.bad("VERSION_REQUIRED");
    String hash = hash(id + ":" + r.expectedVersion());
    return tx.execute(
        t -> {
          db.lockUser(user);
          String replay = db.replay(user, "CANCEL", key, hash, clock.millis());
          if (replay != null) return db.decode(replay, WaitlistResponse.class);
          db.expire(user, clock.millis());
          var i = owned(user, id);
          if (!i.status().equals("CANCELED")) {
            version(i, r.expectedVersion());
            active(i);
            i =
                new Intent(
                    i.id(),
                    user,
                    i.source(),
                    i.conditions(),
                    "CANCELED",
                    i.version() + 1,
                    i.createdAt(),
                    i.consentAt(),
                    null);
            db.update(i);
            db.enqueue("VECTOR", user, clock.millis());
          }
          var result = response(i, List.of());
          db.remember(user, "CANCEL", key, hash, result, clock.millis());
          return result;
        });
  }

  public WaitlistResponse get(String user, String id) {
    var i =
        tx.execute(
            t -> {
              db.lockUser(user);
              db.expire(user, clock.millis());
              return owned(user, id);
            });
    return enriched(response(i, offers(i)));
  }

  public WaitlistListResponse list(String user, String status, String cursor, int limit) {
    if (!Set.of("ACTIVE", "FULFILLED", "CANCELED", "EXPIRED").contains(status)
        || limit < 1
        || limit > 100) throw SearchFailure.bad("INVALID_PAGE");
    String after = "";
    if (cursor != null) {
      String prefix = hash(user + ":" + status) + ":";
      if (!cursor.startsWith(prefix)) throw SearchFailure.bad("INVALID_CURSOR");
      after = cursor.substring(prefix.length());
    }
    final String start = after;
    var all =
        tx.execute(
            t -> {
              db.lockUser(user);
              db.expire(user, clock.millis());
              return db.user(user);
            });
    var items =
        all.stream()
            .filter(i -> i.status().equals(status) && i.id().compareTo(start) > 0)
            .limit(limit + 1)
            .toList();
    boolean next = items.size() > limit;
    var page = items.stream().limit(limit).map(i -> enriched(response(i, offers(i)))).toList();
    return new WaitlistListResponse(
        page,
        new CursorPage(next ? hash(user + ":" + status) + ":" + page.getLast().id() : null, next),
        new WaitlistListResponse.Counts(
            count(all, "ACTIVE"),
            count(all, "FULFILLED"),
            count(all, "CANCELED"),
            count(all, "EXPIRED")));
  }

  public void fulfilled(String user, String id, long expectedVersion, String reservationId) {
    tx.executeWithoutResult(
        t -> {
          db.lockUser(user);
          db.expire(user, clock.millis());
          var i = owned(user, id);
          if (i.status().equals("FULFILLED") && Objects.equals(i.reservationId(), reservationId))
            return;
          version(i, expectedVersion);
          active(i);
          db.update(
              new Intent(
                  i.id(),
                  user,
                  i.source(),
                  i.conditions(),
                  "FULFILLED",
                  i.version() + 1,
                  i.createdAt(),
                  i.consentAt(),
                  reservationId));
          db.enqueue("VECTOR", user, clock.millis());
        });
  }

  private List<WaitlistOfferResponse> offers(Intent i) {
    if (!i.status().equals("ACTIVE")) return List.of();
    var rows =
        db.jdbc()
            .queryForList(
                "select slot_id,publication_version from search_offer where intent_id=? and"
                    + " intent_version=?",
                i.id(),
                i.version());
    var versions = new HashMap<String, Long>();
    for (var row : rows)
      versions.put(
          (String) row.get("SLOT_ID"),
          row.get("PUBLICATION_VERSION") == null
              ? null
              : ((Number) row.get("PUBLICATION_VERSION")).longValue());
    var ids = versions.keySet();
    if (ids.isEmpty()) return List.of();
    return stores.revalidate(ids).stream()
        .filter(
            s ->
                versions.containsKey(s.id())
                    && Objects.equals(versions.get(s.id()), s.publicationVersion()))
        .filter(s -> rules.matches(i.conditions(), s, clock.instant()))
        .sorted(Comparator.comparing(s -> s.startAt().toInstant()))
        .limit(3)
        .map(
            s ->
                new WaitlistOfferResponse(
                    i.id() + ":" + s.id(),
                    s.id(),
                    s.publicationVersion(),
                    s.storeId(),
                    s.storeName(),
                    s.serviceId(),
                    s.serviceName(),
                    s.startAt(),
                    s.endAt(),
                    s.originalPrice(),
                    s.payablePrice(),
                    "KRW",
                    s.imageUrl(),
                    OffsetDateTime.now(clock)))
        .toList();
  }

  private WaitlistResponse enriched(WaitlistResponse response) {
    var c = response.conditions();
    if (c.storeId() == null && c.serviceId() == null) return response;
    var refs = stores.describe(c.storeId(), c.serviceId());
    if (refs == null) throw SearchFailure.unavailable();
    return new WaitlistResponse(
        response.id(),
        response.status(),
        response.source(),
        c,
        refs.store() == null
            ? null
            : new WaitlistResponse.StoreSummary(
                refs.store().id(), refs.store().name(), refs.store().imageUrl()),
        refs.service() == null
            ? null
            : new WaitlistResponse.ServiceSummary(
                refs.service().id(), refs.service().name(), refs.service().imageUrl()),
        response.availability(),
        response.offers(),
        response.reservationId(),
        response.createdAt(),
        response.version());
  }

  private WaitlistResponse response(Intent i, List<WaitlistOfferResponse> offers) {
    return new WaitlistResponse(
        i.id(),
        i.status(),
        i.source(),
        i.conditions(),
        null,
        null,
        i.status().equals("ACTIVE") ? (offers.isEmpty() ? "WAITING" : "AVAILABLE") : "CLOSED",
        offers,
        i.reservationId(),
        Instant.ofEpochMilli(i.createdAt()).atOffset(ZoneOffset.UTC),
        i.version());
  }

  private Intent owned(String user, String id) {
    return db.find(id)
        .filter(i -> i.userId().equals(user))
        .orElseThrow(
            () ->
                new SearchFailure(
                    org.springframework.http.HttpStatus.NOT_FOUND, "WAITLIST_NOT_FOUND"));
  }

  private void active(Intent i) {
    if (!i.status().equals("ACTIVE")) throw SearchFailure.conflict("WAITLIST_CLOSED");
  }

  private void version(Intent i, Long version) {
    if (version == null) throw SearchFailure.bad("VERSION_REQUIRED");
    if (i.version() != version) throw SearchFailure.conflict("VERSION_CONFLICT");
  }

  private void duplicate(List<Intent> active, WaitlistConditions c, String except) {
    if (active.stream().anyMatch(i -> !i.id().equals(except) && i.conditions().equals(c)))
      throw SearchFailure.conflict("DUPLICATE_WAITLIST");
  }

  private int count(List<Intent> all, String status) {
    return (int) all.stream().filter(i -> i.status().equals(status)).count();
  }

  private void followup(Intent i) {
    db.enqueue("INTENT", i.id() + ":" + i.version(), clock.millis());
    db.enqueue("VECTOR", i.userId(), clock.millis());
  }

  private void key(String key) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw SearchFailure.bad("INVALID_IDEMPOTENCY_KEY");
  }

  public static String hash(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
