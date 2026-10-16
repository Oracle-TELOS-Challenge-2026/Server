package com.bintime.store.service;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.service.ConditionRules;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.entity.Shop;
import com.bintime.store.entity.StoreService;
import com.bintime.store.repository.StoreCatalogRepository;
import com.bintime.store.repository.StoreCatalogRepository.Supply;
import com.bintime.store.repository.StoreRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.json.JsonMapper;

/** search가 store 엔티티나 테이블에 직접 접근하지 않도록 공개 조회 계약을 구현한다. */
@Service
@Lazy
@Profile("!api-docs")
@Transactional(readOnly = true)
public class StoreSearchCatalogService implements SearchStoreCatalog {
  private final StoreRepository db;
  private final StoreCatalogRepository catalog;
  private final ConditionRules rules;
  private final JsonMapper json;
  private final Clock clock;

  public StoreSearchCatalogService(
      StoreRepository db,
      StoreCatalogRepository catalog,
      ConditionRules rules,
      JsonMapper json,
      @Qualifier("storeClock") Clock clock) {
    this.db = db;
    this.catalog = catalog;
    this.rules = rules;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public void validate(WaitlistConditions conditions) {
    var c = rules.normalize(conditions, clock.instant());
    Shop shop = c.storeId() == null ? null : shop(c.storeId());
    if (shop != null) require(shop.category.equals(c.category()), 400, "STORE_CATEGORY_MISMATCH");
    if (c.serviceId() != null) {
      StoreService service = service(c.serviceId());
      require(shop == null || service.storeId.equals(shop.id), 400, "SERVICE_STORE_MISMATCH");
      Shop serviceShop = shop == null ? shop(service.storeId) : shop;
      require(serviceShop.category.equals(c.category()), 400, "SERVICE_CATEGORY_MISMATCH");
      require("Y".equals(service.active), 409, "SERVICE_INACTIVE");
    }
    // 대기는 미래 공급을 기다릴 수도 있으므로, 지금 슬롯이 없다는 이유로 거절하지 않는다.
  }

  @Override
  public Page available(WaitlistConditions conditions, String cursor, int limit) {
    StoreCursor.limit(limit);
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    var c = rules.normalize(conditions, now.toInstant());
    validate(c);
    String context = "catalog-available:" + json.writeValueAsString(c);
    String[] decoded = StoreCursor.decode(cursor, context, true);
    OffsetDateTime after = decoded == null ? null : OffsetDateTime.parse(decoded[1]);
    String id = decoded == null ? null : decoded[2];
    var found = new ArrayList<Slot>();
    // 거리로 탈락한 행도 끝까지 순회한다. 원시 조회 한 페이지만 잘라 반환하면 적격 공급이 누락된다.
    while (found.size() <= limit) {
      var batch = catalog.available(c, now, after, id, 100);
      for (Supply supply : batch) {
        Slot slot = dto(supply);
        if (rules.matches(c, slot, now.toInstant())) found.add(slot);
        after = supply.slot().start;
        id = supply.slot().id;
        if (found.size() > limit) break;
      }
      if (batch.size() < 100 || found.size() > limit) break;
    }
    boolean more = found.size() > limit;
    List<Slot> items = List.copyOf(found.subList(0, Math.min(limit, found.size())));
    Slot last = items.isEmpty() ? null : items.getLast();
    return new Page(
        items, more ? StoreCursor.encode(context, last.startAt().toString(), last.id()) : null);
  }

  @Override
  public List<Slot> revalidate(Collection<String> slotIds) {
    require(slotIds != null, 400, "INVALID_SLOT_IDS");
    var unique = new LinkedHashSet<String>();
    for (String id : slotIds) {
      require(id != null && !id.isBlank() && id.length() <= 64, 400, "INVALID_SLOT_IDS");
      unique.add(id);
    }
    var ids = new ArrayList<>(unique);
    var result = new ArrayList<Slot>();
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    // Oracle IN 절의 상한을 피하도록 분할하고, 없는 ID는 결과에서 제외한다.
    for (int start = 0; start < ids.size(); start += 500)
      result.addAll(
          catalog.revalidate(ids.subList(start, Math.min(start + 500, ids.size())), now).stream()
              .map(this::dto)
              .toList());
    result.sort(
        Comparator.comparing(Slot::startAt, Comparator.comparing(OffsetDateTime::toInstant))
            .thenComparing(Slot::id));
    return List.copyOf(result);
  }

  @Override
  public References describe(String storeId, String serviceId) {
    StoreService service = serviceId == null ? null : service(serviceId);
    Shop shop = storeId == null ? (service == null ? null : shop(service.storeId)) : shop(storeId);
    require(service == null || service.storeId.equals(shop.id), 400, "SERVICE_STORE_MISMATCH");
    return new References(
        shop == null ? null : new Reference(shop.id, shop.name, shop.imageUrl),
        service == null ? null : new Reference(service.id, service.name, null));
  }

  @Override
  @Transactional(
      readOnly = true,
      isolation = Isolation.SERIALIZABLE,
      propagation = Propagation.REQUIRES_NEW)
  public StorePage export(String cursor, int limit) {
    StoreCursor.limit(limit);
    String[] after = StoreCursor.decode(cursor, "catalog-export", false);
    var shops =
        after == null
            ? db.limited(Shop.class, "order by e.id", limit + 1)
            : db.limited(Shop.class, "where e.id>?1 order by e.id", limit + 1, after[2]);
    boolean more = shops.size() > limit;
    var items = new ArrayList<StoreDocument>();
    var now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    // 문서와 버전을 같은 스냅샷에서 읽는다. 미커밋 업무 변경은 검색으로 내보내지 않는다.
    for (Shop shop : shops.subList(0, Math.min(limit, shops.size()))) {
      var services = db.query(StoreService.class, "where e.storeId=?1 order by e.id", shop.id);
      String description = description(shop, services);
      var slots = catalog.storeSupply(shop.id, now).stream().map(this::dto).toList();
      items.add(
          new StoreDocument(
              shop.id,
              shop.name,
              shop.category,
              shop.imageUrl,
              shop.ratingAverage,
              shop.ratingCount,
              shop.latitude,
              shop.longitude,
              description,
              shop.searchVersion,
              slots));
    }
    String last = items.isEmpty() ? null : items.getLast().id();
    return new StorePage(
        List.copyOf(items), more ? StoreCursor.encode("catalog-export", last, last) : null);
  }

  private Shop shop(String id) {
    require(!id.isBlank() && id.length() <= 64, 400, "INVALID_STORE_ID");
    Shop shop = db.find(Shop.class, id);
    require(shop != null, 404, "STORE_NOT_FOUND");
    return shop;
  }

  private StoreService service(String id) {
    require(!id.isBlank() && id.length() <= 64, 400, "INVALID_SERVICE_ID");
    StoreService service = db.find(StoreService.class, id);
    require(service != null, 404, "SERVICE_NOT_FOUND");
    return service;
  }

  private String description(Shop shop, List<StoreService> services) {
    var parts = new ArrayList<String>();
    parts.add(shop.name);
    parts.add(shop.category);
    if (shop.description != null && !shop.description.isBlank()) parts.add(shop.description);
    services.forEach(service -> parts.add(service.name));
    return String.join("\n", parts);
  }

  private Slot dto(Supply supply) {
    var s = supply.slot();
    var p = supply.shop();
    var v = supply.service();
    return new Slot(
        s.id,
        p.id,
        p.name,
        p.category,
        v.id,
        v.name,
        p.latitude,
        p.longitude,
        s.start,
        s.end,
        s.originalPrice,
        s.payable,
        "REGULAR".equals(s.source) ? "REGULAR" : "VACANCY",
        s.version,
        s.publicationVersion,
        p.imageUrl,
        description(p, List.of(v)));
  }
}
