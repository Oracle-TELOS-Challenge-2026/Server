package com.bintime.search.service;

import com.bintime.common.dto.CursorPage;
import com.bintime.search.dto.*;
import com.bintime.search.port.StoreIndexPort;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.Slot;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class StoreSearchService {
  private final StoreIndexPort index;
  private final SearchStoreCatalog stores;
  private final SearchCursor cursors;
  private final Clock clock;
  private final ObjectProvider<PersonalizationService> vectors;
  private final JsonMapper json;

  public StoreSearchService(
      StoreIndexPort index,
      SearchStoreCatalog stores,
      SearchCursor cursors,
      Clock clock,
      ObjectProvider<PersonalizationService> vectors,
      JsonMapper json) {
    this.index = index;
    this.stores = stores;
    this.cursors = cursors;
    this.clock = clock;
    this.vectors = vectors;
    this.json = json;
  }

  public StoreSearchResponse search(
      double lat,
      double lon,
      int radius,
      String keyword,
      String category,
      OffsetDateTime from,
      OffsetDateTime to,
      boolean vacancy,
      String sort,
      String cursor,
      int limit,
      String user) {
    ConditionRules.coordinates(lat, lon);
    if (radius < 100
        || radius > 10000
        || limit < 1
        || limit > 50
        || !Set.of("DISTANCE", "PRICE", "RECOMMENDED").contains(sort)
        || category != null && !ConditionRules.CATEGORIES.contains(category)
        || keyword != null && keyword.length() > 200
        || from != null && to != null && !from.isBefore(to))
      throw SearchFailure.bad("INVALID_SEARCH");
    var state = cursor == null ? null : cursors.decode(cursor);
    long snapshot = state == null ? clock.millis() : state.snapshotAt();
    var at = Instant.ofEpochMilli(snapshot).atZone(clock.getZone()).toOffsetDateTime();
    Query q =
        new Query(
            lat, lon, radius, keyword, category, from == null ? at : from, to, vacancy, sort, at);
    float[] vector = null;
    if (sort.equals("RECOMMENDED")) {
      var service = vectors.getIfAvailable();
      if (service != null) vector = service.vector(user);
    }
    String fingerprint = json.writeValueAsString(q) + ":" + Objects.toString(user, "") + ":";
    String hash = WaitlistService.hash(fingerprint + Arrays.hashCode(vector));
    String distanceHash = WaitlistService.hash(fingerprint + Arrays.hashCode((float[]) null));
    if (state != null && !hash.equals(state.queryHash())) {
      if (distanceHash.equals(state.queryHash())) {
        vector = null;
        hash = distanceHash;
      } else throw SearchFailure.bad("CURSOR_CONDITIONS_CHANGED");
    }
    String pit = state == null ? index.open() : state.pit();
    List<Object> after = state == null ? List.of() : state.after();
    var items = new ArrayList<StoreSearchResponse.Item>();
    boolean more = true;
    try {
      boolean lookahead = false;
      while (!lookahead && more) {
        StoreIndexPort.Page page;
        try {
          page = index.search(q, pit, after, vector, Math.min(50, limit - items.size() + 1));
        } catch (SearchFailure failure) {
          // 기존 페이지의 정렬을 바꾸면 누락이 생기므로 최초 검색만 거리순으로 대체한다.
          if (vector == null || state != null || !items.isEmpty() || !after.isEmpty())
            throw failure;
          vector = null;
          hash = distanceHash;
          page = index.search(q, pit, after, null, Math.min(50, limit - items.size() + 1));
        }
        pit = page.pit();
        if (page.hits().isEmpty()) {
          more = false;
          break;
        }
        var ids =
            page.hits().stream()
                .flatMap(h -> h.store().slots().stream())
                .map(Slot::id)
                .distinct()
                .toList();
        var valid =
            stores.revalidate(ids).stream()
                .collect(java.util.stream.Collectors.toMap(Slot::id, s -> s, (a, b) -> a));
        for (var hit : page.hits()) {

          var doc = hit.store();
          var candidates =
              doc.slots().stream()
                  .filter(
                      s -> valid.containsKey(s.id()) && valid.get(s.id()).version() == s.version())
                  .map(s -> valid.get(s.id()))
                  .filter(s -> eligible(q, s))
                  .sorted(slotOrder(sort))
                  .toList();
          if (candidates.isEmpty()) {
            after = hit.sort();
            continue;
          }
          if (items.size() == limit) {
            lookahead = true;
            break;
          }
          after = hit.sort();
          Slot best = candidates.getFirst();
          items.add(
              new StoreSearchResponse.Item(
                  new StoreSearchResponse.ItemStore(
                      doc.id(),
                      doc.name(),
                      doc.category(),
                      doc.imageUrl(),
                      doc.rating() == null
                          ? null
                          : new StoreSearchResponse.ItemStoreRating(
                              doc.rating(), doc.ratingCount())),
                  new StoreSearchResponse.ItemLocation(doc.latitude(), doc.longitude()),
                  (int)
                      Math.round(
                          ConditionRules.distance(lat, lon, doc.latitude(), doc.longitude())),
                  null,
                  new StoreSearchResponse.ItemBestSlot(
                      best.id(),
                      best.serviceId(),
                      best.serviceName(),
                      best.startAt(),
                      best.originalPrice(),
                      best.payablePrice(),
                      "KRW",
                      best.source(),
                      best.version()),
                  List.of(vector == null ? "NEARBY" : "PREFERENCE_SIMILARITY")));
        }
      }
      more = lookahead;
      long expires = state == null ? snapshot + 300000 : state.expiresAt();
      String next =
          more ? cursors.encode(new SearchCursor.State(pit, after, hash, expires, snapshot)) : null;
      if (!more) index.close(pit);
      return new StoreSearchResponse(items, new CursorPage(next, more), at);
    } catch (Exception e) {
      index.close(pit);
      throw e;
    }
  }

  private boolean eligible(Query q, Slot s) {
    return s.startAt().toInstant().isAfter(clock.instant())
        && !s.startAt().isBefore(q.from())
        && (q.to() == null || !s.endAt().isAfter(q.to()))
        && (!q.vacancyOnly() || s.source().equals("VACANCY"))
        && (q.category() == null || q.category().equals(s.category()))
        && ConditionRules.distance(q.latitude(), q.longitude(), s.latitude(), s.longitude())
            <= q.radiusMeters();
  }

  private Comparator<Slot> slotOrder(String sort) {
    Comparator<Slot> order = Comparator.comparing(s -> s.startAt().toInstant());
    if (sort.equals("PRICE"))
      order = Comparator.comparingLong(Slot::payablePrice).thenComparing(order);
    return order.thenComparing(Slot::id);
  }

  public record Query(
      double latitude,
      double longitude,
      int radiusMeters,
      String keyword,
      String category,
      OffsetDateTime from,
      OffsetDateTime to,
      boolean vacancyOnly,
      String sort,
      OffsetDateTime snapshotAt) {}
}
