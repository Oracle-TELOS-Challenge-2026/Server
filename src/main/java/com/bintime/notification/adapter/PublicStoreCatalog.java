package com.bintime.notification.adapter;

import com.bintime.common.web.ApiBusinessException;
import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.service.ConditionRules;
import com.bintime.search.service.SearchFailure;
import com.bintime.search.service.WaitlistService;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.dto.ReservationSlotResponse;
import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceResponse;
import com.bintime.store.service.StoreApplicationService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** 매장 공개 서비스를 사용하는 단일 앱 연동. 매장 Repository나 테이블에 직접 접근하지 않는다. */
@Component
@Primary
@Lazy
@Profile("!api-docs")
public class PublicStoreCatalog implements SearchStoreCatalog {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private final StoreApplicationService stores;
  private final ConditionRules rules;
  private final Clock clock;
  private final JsonMapper json;
  private final TransactionTemplate reads;

  public PublicStoreCatalog(
      @Lazy StoreApplicationService stores,
      ConditionRules rules,
      Clock clock,
      JsonMapper json,
      PlatformTransactionManager transactions) {
    this.stores = stores;
    this.rules = rules;
    this.clock = clock;
    this.json = json;
    this.reads = new TransactionTemplate(transactions);
    // 공개 슬롯 조회는 만료 처리와 매장 잠금을 포함한다. 잘못된 매장 404도 호출자 트랜잭션과 분리한다.
    this.reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public void validate(WaitlistConditions conditions) {
    if (conditions == null
        || !ConditionRules.CATEGORIES.contains(Objects.toString(conditions.category(), "")))
      throw SearchFailure.bad("INVALID_CONDITIONS");
    StoreDetailResponse store = conditions.storeId() == null ? null : store(conditions.storeId());
    if (store != null && !store.category().equals(conditions.category()))
      throw SearchFailure.bad("STORE_CATEGORY_MISMATCH");
    if (conditions.serviceId() != null) {
      LocatedService service = locateService(conditions.storeId(), conditions.serviceId());
      if (!service.store().category().equals(conditions.category()))
        throw SearchFailure.bad("SERVICE_CATEGORY_MISMATCH");
      if (!Boolean.TRUE.equals(service.service().active()))
        throw SearchFailure.bad("SERVICE_INACTIVE");
    }
  }

  @Override
  public References describe(String storeId, String serviceId) {
    if (serviceId != null) {
      var found = locateService(storeId, serviceId);
      return new References(
          reference(found.store()),
          new Reference(found.service().id(), found.service().name(), null));
    }
    return new References(storeId == null ? null : reference(store(storeId)), null);
  }

  @Override
  public List<Slot> revalidate(Collection<String> slotIds) {
    if (slotIds == null || slotIds.stream().anyMatch(Objects::isNull))
      throw SearchFailure.bad("INVALID_RESOURCE_ID");
    if (slotIds.isEmpty()) return List.of();
    var remaining = new LinkedHashSet<>(slotIds);
    var result = new LinkedHashMap<String, Slot>();
    // 초기 매장 규모에서는 공개 조회를 순회한다. 규모가 커지면 매장 측 일괄 조회 계약으로 교체한다.
    for (String storeId : storeIds()) {
      StoreDetailResponse store = null;
      Map<String, StoreServiceResponse> services = null;
      for (String slotId : List.copyOf(remaining)) {
        ReservationSlotResponse slot = optional(() -> stores.getSlot(storeId, slotId));
        if (slot == null) continue;
        remaining.remove(slotId);
        if (!bookable(slot)) continue;
        if (store == null) store = store(storeId);
        if (services == null) services = services(storeId);
        var service = services.get(slot.serviceId());
        if (service != null && Boolean.TRUE.equals(service.active()))
          result.put(slot.id(), slot(store, service, slot));
      }
      if (remaining.isEmpty()) break;
    }
    return slotIds.stream().distinct().map(result::get).filter(Objects::nonNull).toList();
  }

  @Override
  public Page available(WaitlistConditions conditions, String cursor, int limit) {
    if (limit < 1 || limit > 100) throw SearchFailure.bad("INVALID_LIMIT");
    WaitlistConditions normalized = rules.normalize(conditions, clock.instant());
    String fingerprint = WaitlistService.hash(json.writeValueAsString(normalized));
    Position after = decode(cursor, fingerprint);
    List<Slot> result = new ArrayList<>();
    List<String> ids = normalized.storeId() == null ? storeIds() : List.of(normalized.storeId());
    for (String storeId : ids) {
      if (after != null && storeId.compareTo(after.storeId()) < 0) continue;
      StoreDetailResponse store = store(storeId);
      if (!store.category().equals(normalized.category())) continue;
      Map<String, StoreServiceResponse> services = services(storeId);
      if (normalized.serviceId() != null && !services.containsKey(normalized.serviceId())) continue;
      List<Slot> matching = new ArrayList<>();
      LocalDate from = normalized.windowStart().atZoneSameInstant(SEOUL).toLocalDate();
      LocalDate today = clock.instant().atZone(SEOUL).toLocalDate();
      if (from.isBefore(today)) from = today;
      LocalDate last = normalized.windowEnd().atZoneSameInstant(SEOUL).toLocalDate();
      while (!from.isAfter(last)) {
        LocalDate through = ChronoUnit.DAYS.between(from, last) > 30 ? from.plusDays(30) : last;
        LocalDate first = from;
        String pageCursor = null;
        do {
          String next = pageCursor;
          var page =
              call(() -> stores.slots(storeId, first, through, normalized.serviceId(), next, 100));
          for (var candidate : page.items()) {
            var service = services.get(candidate.serviceId());
            if (!bookable(candidate) || service == null || !Boolean.TRUE.equals(service.active()))
              continue;
            Slot slot = slot(store, service, candidate);
            if (rules.matches(normalized, slot, clock.instant())
                && (after == null
                    || storeId.compareTo(after.storeId()) > 0
                    || slot.id().compareTo(after.slotId()) > 0)) matching.add(slot);
          }
          pageCursor = page.page().hasNext() ? page.page().nextCursor() : null;
        } while (pageCursor != null);
        if (through.equals(last)) break;
        from = through.plusDays(1);
      }
      result.addAll(matching.stream().sorted(Comparator.comparing(Slot::id)).toList());
      if (result.size() > limit) break;
    }
    boolean more = result.size() > limit;
    List<Slot> selected = result.stream().limit(limit).toList();
    String next = more ? encode(fingerprint, selected.getLast()) : null;
    return new Page(selected, next);
  }

  @Override
  public StorePage export(String cursor, int limit) {
    // 공개 API에는 전체 미래 슬롯과 검색 문서의 통합 버전이 없다. 불완전한 인덱스를 만들지 않는다.
    throw SearchFailure.unavailable();
  }

  private List<String> storeIds() {
    return call(stores::storeIds).stream().distinct().sorted().toList();
  }

  private StoreDetailResponse store(String id) {
    StoreDetailResponse value = optional(() -> stores.getStore(id));
    if (value == null) throw new SearchFailure(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND");
    return value;
  }

  private Map<String, StoreServiceResponse> services(String storeId) {
    Map<String, StoreServiceResponse> result = new LinkedHashMap<>();
    String cursor = null;
    do {
      String next = cursor;
      var page = call(() -> stores.services(storeId, next, 100));
      for (var service : page.items()) result.put(service.id(), service);
      cursor = page.page().hasNext() ? page.page().nextCursor() : null;
    } while (cursor != null);
    return result;
  }

  private LocatedService locateService(String storeId, String serviceId) {
    for (String id : storeId == null ? storeIds() : List.of(storeId)) {
      var service = services(id).get(serviceId);
      if (service != null) return new LocatedService(store(id), service);
    }
    throw new SearchFailure(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND");
  }

  private boolean bookable(ReservationSlotResponse slot) {
    return Boolean.TRUE.equals(slot.bookable())
        && "AVAILABLE".equals(slot.status())
        && slot.startAt().toInstant().isAfter(clock.instant())
        && slot.endAt().isAfter(slot.startAt());
  }

  private Slot slot(
      StoreDetailResponse store, StoreServiceResponse service, ReservationSlotResponse slot) {
    return new Slot(
        slot.id(),
        store.id(),
        store.name(),
        store.category(),
        service.id(),
        service.name(),
        store.location().latitude(),
        store.location().longitude(),
        slot.startAt(),
        slot.endAt(),
        slot.price().original(),
        slot.price().payable(),
        slot.source(),
        slot.version(),
        slot.publicationVersion(),
        store.imageUrl(),
        store.description());
  }

  private Reference reference(StoreDetailResponse store) {
    return new Reference(store.id(), store.name(), store.imageUrl());
  }

  private <T> T optional(Supplier<T> action) {
    try {
      return call(action);
    } catch (SearchFailure failure) {
      if (failure.status == HttpStatus.NOT_FOUND) return null;
      throw failure;
    }
  }

  private <T> T call(Supplier<T> action) {
    try {
      return reads.execute(status -> action.get());
    } catch (ApiBusinessException failure) {
      throw new SearchFailure(HttpStatus.valueOf(failure.status), failure.code);
    }
  }

  private record LocatedService(StoreDetailResponse store, StoreServiceResponse service) {}

  private record Position(String storeId, String slotId) {}

  private String encode(String fingerprint, Slot slot) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            (fingerprint + "|" + slot.storeId() + "|" + slot.id())
                .getBytes(StandardCharsets.UTF_8));
  }

  private Position decode(String cursor, String fingerprint) {
    if (cursor == null) return null;
    try {
      if (cursor.length() > 1024) throw new IllegalArgumentException();
      String[] parts =
          new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
              .split("\\|", -1);
      if (parts.length != 3
          || !fingerprint.equals(parts[0])
          || !parts[1].matches("[A-Za-z0-9_-]{1,64}")
          || !parts[2].matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException();
      return new Position(parts[1], parts[2]);
    } catch (RuntimeException malformed) {
      throw SearchFailure.bad("INVALID_CURSOR");
    }
  }
}
