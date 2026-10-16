package com.bintime.store.service;

import static com.bintime.common.web.ApiBusinessException.require;

import com.bintime.common.auth.Actor;
import com.bintime.common.dto.CursorPage;
import com.bintime.search.service.WaitlistReservationService;
import com.bintime.store.contract.StoreReservationPort.ProposalCommand;
import com.bintime.store.dto.*;
import com.bintime.store.entity.*;
import com.bintime.store.repository.StoreRepository;
import com.bintime.user.service.UserAccountAccess;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Service
@Lazy
@Transactional
public class StoreApplicationService {
  private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
  private static final Set<String> CATEGORIES = Set.of("NAIL", "HAIR", "EYELASH", "SKIN");
  private static final List<String> DAYS = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
  private final StoreRepository db;
  private final UserAccountAccess users;
  private final WaitlistReservationService waitlists;
  private final JsonMapper json;
  private final Clock clock;

  public StoreApplicationService(
      StoreRepository db,
      UserAccountAccess users,
      WaitlistReservationService waitlists,
      JsonMapper json,
      Clock clock) {
    this.db = db;
    this.users = users;
    this.waitlists = waitlists;
    this.json =
        json.rebuild()
            .disable(tools.jackson.databind.cfg.DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE)
            .build();
    this.clock = clock;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .atZoneSameInstant(SEOUL)
        .toOffsetDateTime()
        .truncatedTo(ChronoUnit.MICROS);
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "");
  }

  private static String resource(String storeId) {
    return "resource_" + storeId;
  }

  private static void text(String value, int max) {
    require(value != null && !value.isBlank() && value.length() <= max, 400, "INVALID_INPUT");
  }

  private static void price(Long value) {
    require(value != null && value >= 0 && value <= 10000000, 400, "INVALID_PRICE");
  }

  private static void version(Long expected, Long actual) {
    require(expected != null && expected >= 1, 400, "INVALID_VERSION");
    require(expected.equals(actual), 409, "VERSION_CONFLICT");
  }

  private static void ownerRole(Actor a) {
    require("OWNER".equals(a.role()), 403, "OWNER_REQUIRED");
  }

  private Shop shop(String id) {
    Shop s = db.find(Shop.class, id);
    require(s != null, 404, "STORE_NOT_FOUND");
    return s;
  }

  private Shop lockedShop(String id) {
    Shop s = db.lock(Shop.class, id);
    require(s != null, 404, "STORE_NOT_FOUND");
    return s;
  }

  private Shop owned(Actor a, String id, boolean lock) {
    ownerRole(a);
    Shop s = lock ? lockedShop(id) : shop(id);
    require(s.ownerId.equals(a.id()), 403, "STORE_FORBIDDEN");
    return s;
  }

  private StoreService service(String storeId, String id) {
    require(id != null, 400, "INVALID_SERVICE");
    StoreService s = db.find(StoreService.class, id);
    require(s != null && s.storeId.equals(storeId), 404, "SERVICE_NOT_FOUND");
    return s;
  }

  private Slot slot(String storeId, String id) {
    Slot s = db.find(Slot.class, id);
    require(s != null && s.storeId.equals(storeId), 404, "SLOT_NOT_FOUND");
    return s;
  }

  private Reservation reservation(String id) {
    Reservation r = db.find(Reservation.class, id);
    require(r != null, 404, "RESERVATION_NOT_FOUND");
    return r;
  }

  private void access(Actor a, Reservation r) {
    require(
        r.userId.equals(a.id())
            || ("OWNER".equals(a.role()) && shop(r.storeId).ownerId.equals(a.id())),
        403,
        "RESERVATION_FORBIDDEN");
  }

  private void lockActor(Actor a) {
    users.lock(a.id());
    // 계정 잠금 덕분에 최초 잠금 행 생성도 경합하지 않는다. 이후 모든 쓰기는 고객 → 매장 순서다.
    if (db.lock(CustomerLock.class, a.id()) == null) {
      CustomerLock l = new CustomerLock();
      l.id = a.id();
      db.save(l);
      db.flush();
    }
  }

  private <T> T once(
      Actor a,
      String operation,
      String key,
      Object request,
      int status,
      Class<T> type,
      Supplier<T> action) {
    text(key, 200);
    require(key.chars().allMatch(c -> c >= 33 && c <= 126), 400, "INVALID_IDEMPOTENCY_KEY");
    lockActor(a);
    String operationName = operation.split(":", 2)[0];
    String rid = StoreCursor.hash(json.writeValueAsString(List.of(a.id(), operationName, key)));
    String fingerprint = StoreCursor.hash(json.writeValueAsString(List.of(operation, request)));
    IdempotencyRecord record = db.find(IdempotencyRecord.class, rid);
    if (record != null && record.expiresAt.isAfter(now())) {
      require(record.fingerprint.equals(fingerprint), 409, "IDEMPOTENCY_KEY_REUSED");
      return json.readValue(record.result, type);
    }
    T result = action.get();
    boolean fresh = record == null;
    if (fresh) {
      record = new IdempotencyRecord();
      record.id = rid;
    }
    record.actorId = a.id();
    record.operation = operationName;
    record.key = key;
    record.fingerprint = fingerprint;
    record.result = json.writeValueAsString(result);
    record.status = status;
    record.expiresAt = now().plusHours(24);
    if (fresh) db.save(record);
    db.flush();
    return result;
  }

  private List<UpdateStoreRequest.OpeningHourItem> hours(Shop s) {
    return json.readValue(
        s.openingHours, new TypeReference<List<UpdateStoreRequest.OpeningHourItem>>() {});
  }

  private void validateHours(List<UpdateStoreRequest.OpeningHourItem> hours) {
    require(hours != null && hours.size() == 7, 400, "INVALID_OPENING_HOURS");
    Set<String> seen = new HashSet<>();
    for (var h : hours) {
      require(
          h != null
              && h.dayOfWeek() != null
              && DAYS.contains(h.dayOfWeek())
              && seen.add(h.dayOfWeek())
              && h.closed() != null,
          400,
          "INVALID_OPENING_HOURS");
      require(
          h.closed()
              ? h.opensAt() == null && h.closesAt() == null
              : h.opensAt() != null
                  && h.closesAt() != null
                  && h.opensAt().isBefore(h.closesAt())
                  && h.opensAt().getSecond() == 0
                  && h.opensAt().getNano() == 0
                  && h.closesAt().getSecond() == 0
                  && h.closesAt().getNano() == 0,
          400,
          "INVALID_OPENING_HOURS");
    }
  }

  private void inHours(Shop s, OffsetDateTime start, OffsetDateTime end) {
    var begin = start.atZoneSameInstant(SEOUL);
    var finish = end.atZoneSameInstant(SEOUL);
    var h =
        hours(s).stream()
            .filter(x -> x.dayOfWeek().equals(DAYS.get(begin.getDayOfWeek().getValue() - 1)))
            .findFirst()
            .orElseThrow();
    require(
        !h.closed()
            && begin.toLocalDate().equals(finish.toLocalDate())
            && !begin.toLocalTime().isBefore(h.opensAt())
            && !finish.toLocalTime().isAfter(h.closesAt()),
        409,
        "OUTSIDE_OPENING_HOURS");
  }

  private OffsetDateTime normalize(OffsetDateTime value) {
    require(value != null, 400, "INVALID_TIME");
    return value.atZoneSameInstant(SEOUL).toOffsetDateTime().truncatedTo(ChronoUnit.MICROS);
  }

  private void event(Slot s, String type) {
    // 호출자는 매장 잠금을 보유한다. 업무 상태와 검색 버전은 같은 트랜잭션으로 커밋한다.
    shop(s.storeId).searchVersion++;
    StoreEvent e = new StoreEvent();
    e.id = id("evt_");
    e.type = type;
    e.storeId = s.storeId;
    e.slotId = s.id;
    e.publicationVersion = s.publicationVersion;
    e.slotVersion = s.version;
    e.status = "QUEUED";
    e.createdAt = now();
    e.availableAt = e.createdAt;
    e.payload =
        json.writeValueAsString(
            Map.of(
                "eventId",
                e.id,
                "type",
                type,
                "storeId",
                s.storeId,
                "slot",
                slotDto(s),
                "occurredAt",
                e.createdAt));
    db.save(e);
  }

  private void changed(Shop shop) {
    StoreEvent e = new StoreEvent();
    e.id = id("evt_");
    e.type = "STORE_CHANGED";
    e.storeId = shop.id;
    e.status = "QUEUED";
    e.createdAt = now();
    e.availableAt = e.createdAt;
    e.payload = json.writeValueAsString(Map.of("eventId", e.id, "storeId", shop.id));
    db.save(e);
  }

  private boolean expired(Slot s) {
    return ("AVAILABLE".equals(s.status) && !s.start.isAfter(now()))
        || (Set.of("DRAFT", "CLOSED").contains(s.status)
            && !s.availableUntil.isAfter(now())
            && !"REGULAR".equals(s.source));
  }

  private void expire(Shop shop) {
    for (Slot s :
        db.query(
            Slot.class,
            "where e.storeId=?1 and e.status in ('AVAILABLE','DRAFT','CLOSED')",
            shop.id))
      if (expired(s)) {
        s.status = "EXPIRED";
        s.version++;
        event(s, "EXPIRED");
      }
  }

  public void expireStore(String storeId) {
    expire(lockedShop(storeId));
  }

  @Transactional(readOnly = true)
  public List<String> storeIds() {
    return db.query(Shop.class, "").stream().map(s -> s.id).toList();
  }

  public CategoryListResponse categories() {
    return new CategoryListResponse(
        List.of(
            new CategoryListResponse.Item("NAIL", "네일"),
            new CategoryListResponse.Item("HAIR", "헤어"),
            new CategoryListResponse.Item("EYELASH", "속눈썹"),
            new CategoryListResponse.Item("SKIN", "피부관리")));
  }

  public StoreDetailResponse getStore(String id) {
    return shopDto(shop(id));
  }

  public StoreDetailResponse createStore(Actor a, String key, CreateStoreRequest r) {
    return once(
        a,
        "CREATE_STORE",
        key,
        r,
        201,
        StoreDetailResponse.class,
        () -> {
          text(r.name(), 100);
          text(r.address(), 300);
          require(
              CATEGORIES.contains(r.category() == null ? "" : r.category()),
              400,
              "INVALID_CATEGORY");
          require(
              r.location() != null
                  && r.location().latitude() != null
                  && r.location().longitude() != null
                  && Double.isFinite(r.location().latitude())
                  && Double.isFinite(r.location().longitude())
                  && Math.abs(r.location().latitude()) <= 90
                  && Math.abs(r.location().longitude()) <= 180,
              400,
              "INVALID_LOCATION");
          require(
              r.description() == null || r.description().length() <= 1000,
              400,
              "INVALID_DESCRIPTION");
          require(r.imageUrl() == null || r.imageUrl().length() <= 2048, 400, "INVALID_IMAGE_URL");
          validateHours(r.openingHours());
          users.grantOwner(a.id());
          Shop s = new Shop();
          s.id = id("shop_");
          s.ownerId = a.id();
          s.name = r.name();
          s.category = r.category();
          s.address = r.address();
          s.latitude = r.location().latitude();
          s.longitude = r.location().longitude();
          s.description = r.description();
          s.imageUrl = r.imageUrl();
          s.openingHours = json.writeValueAsString(r.openingHours());
          s.timezone = "Asia/Seoul";
          s.ratingAverage = 0d;
          s.ratingCount = 0;
          s.version = 1L;
          s.createdAt = now();
          db.save(s);
          changed(s);
          return shopDto(s);
        });
  }

  public StoreServiceResponse createService(
      Actor a, String storeId, String key, CreateStoreServiceRequest r) {
    ownerRole(a);
    return once(
        a,
        "CREATE_SERVICE:" + storeId,
        key,
        r,
        201,
        StoreServiceResponse.class,
        () -> {
          Shop shop = owned(a, storeId, true);
          text(r.name(), 100);
          price(r.basePrice());
          require(
              r.durationMinutes() != null
                  && r.durationMinutes() >= 15
                  && r.durationMinutes() <= 480
                  && r.durationMinutes() % 5 == 0
                  && r.active() != null,
              400,
              "INVALID_SERVICE");
          StoreService s = new StoreService();
          s.id = id("svc_");
          s.storeId = storeId;
          s.name = r.name();
          s.duration = r.durationMinutes();
          s.price = r.basePrice();
          s.active = r.active() ? "Y" : "N";
          s.version = 1L;
          db.save(s);
          shop.searchVersion++;
          changed(shop);
          return serviceDto(shop, s);
        });
  }

  public ReservationSlotResponse createSlot(
      Actor a, String storeId, String key, CreateSlotRequest r) {
    ownerRole(a);
    return once(
        a,
        "CREATE_SLOT:" + storeId,
        key,
        r,
        201,
        ReservationSlotResponse.class,
        () -> {
          Shop shop = owned(a, storeId, true);
          expire(shop);
          StoreService service = service(storeId, r.serviceId());
          require("Y".equals(service.active), 409, "SERVICE_INACTIVE");
          OffsetDateTime start = normalize(r.startAt()), end = start.plusMinutes(service.duration);
          require(start.isAfter(now()), 409, "SLOT_EXPIRED");
          inHours(shop, start, end);
          supplyConflict(storeId, start, end, null);
          Slot s = new Slot();
          s.id = id("slot_");
          s.storeId = storeId;
          s.serviceId = service.id;
          s.start = start;
          s.end = end;
          s.availableFrom = start;
          s.availableUntil = end;
          s.originalPrice = service.price;
          s.payable = service.price;
          s.status = "AVAILABLE";
          s.source = "REGULAR";
          s.version = 1L;
          s.createdAt = now();
          db.save(s);
          event(s, "CREATED");
          return ownerSlotDto(s);
        });
  }

  public StoreDetailResponse updateStore(Actor a, String storeId, UpdateStoreRequest r) {
    lockActor(a);
    Shop s = owned(a, storeId, true);
    version(r.expectedVersion(), s.version);
    require(r.description() != null || r.openingHours() != null, 400, "EMPTY_PATCH");
    if (r.description() != null) {
      require(r.description().length() <= 1000, 400, "INVALID_DESCRIPTION");
      s.description = r.description();
    }
    if (r.openingHours() != null) {
      validateHours(r.openingHours());
      s.openingHours = json.writeValueAsString(r.openingHours());
    }
    s.version++;
    s.searchVersion++;
    changed(s);
    return shopDto(s);
  }

  public StoreServiceResponse updateService(
      Actor a, String storeId, String serviceId, UpdateStoreServiceRequest r) {
    lockActor(a);
    Shop shop = owned(a, storeId, true);
    StoreService s = service(storeId, serviceId);
    version(r.expectedVersion(), s.version);
    require(r.name() != null || r.basePrice() != null || r.active() != null, 400, "EMPTY_PATCH");
    if (r.name() != null) {
      text(r.name(), 100);
      s.name = r.name();
    }
    if (r.basePrice() != null) {
      price(r.basePrice());
      s.price = r.basePrice();
    }
    if (r.active() != null) s.active = r.active() ? "Y" : "N";
    s.version++;
    shop.searchVersion++;
    changed(shop);
    return serviceDto(shop, s);
  }

  private void supplyConflict(
      String storeId, OffsetDateTime start, OffsetDateTime end, String excluded) {
    var supply =
        db.query(
            Slot.class,
            "where e.storeId=?1 and e.status='AVAILABLE' and e.start<?2 and e.end>?3",
            storeId,
            end,
            start);
    require(
        supply.stream().noneMatch(s -> !s.id.equals(excluded) && s.start.isAfter(now())),
        409,
        "SLOT_TIME_CONFLICT");
    require(
        db.query(
                Reservation.class,
                "where e.storeId=?1 and e.status='CONFIRMED' and e.start<?2 and e.end>?3",
                storeId,
                end,
                start)
            .isEmpty(),
        409,
        "RESERVATION_TIME_CONFLICT");
  }

  private record Page<T>(List<T> items, CursorPage page) {}

  private <T> Page<T> page(
      Class<T> type,
      String where,
      List<Object> args,
      String field,
      boolean desc,
      String cursor,
      String context,
      Integer limit,
      Function<T, String> value,
      Function<T, String> identity) {
    int size = StoreCursor.limit(limit);
    String[] p = StoreCursor.decode(cursor, context, !field.equals("id"));
    String op = desc ? "<" : ">";
    if (p != null) {
      int n = args.size() + 1;
      where +=
          " and (e."
              + field
              + op
              + "?"
              + n
              + " or (e."
              + field
              + "=?"
              + n
              + " and e.id"
              + op
              + "?"
              + (n + 1)
              + "))";
      args.add(field.equals("id") ? p[1] : OffsetDateTime.parse(p[1]));
      args.add(p[2]);
    }
    var rows =
        db.limited(
            type,
            where
                + " order by e."
                + field
                + (desc ? " desc" : " asc")
                + ", e.id"
                + (desc ? " desc" : " asc"),
            size + 1,
            args.toArray());
    boolean more = rows.size() > size;
    var selected = List.copyOf(rows.subList(0, Math.min(size, rows.size())));
    T last = selected.isEmpty() ? null : selected.getLast();
    return new Page<>(
        selected,
        new CursorPage(
            more ? StoreCursor.encode(context, value.apply(last), identity.apply(last)) : null,
            more));
  }

  public StoreServiceListResponse services(String storeId, String cursor, Integer limit) {
    Shop shop = shop(storeId);
    var page =
        page(
            StoreService.class,
            "where e.storeId=?1",
            new ArrayList<>(List.of(storeId)),
            "id",
            false,
            cursor,
            "services:" + storeId,
            limit,
            s -> s.id,
            s -> s.id);
    return new StoreServiceListResponse(
        page.items.stream().map(s -> serviceDto(shop, s)).toList(), page.page);
  }

  public ReservationSlotListResponse slots(
      String storeId,
      LocalDate from,
      LocalDate to,
      String serviceId,
      String cursor,
      Integer limit) {
    require(
        from != null && to != null && !from.isAfter(to) && ChronoUnit.DAYS.between(from, to) < 31,
        400,
        "INVALID_DATE_RANGE");
    Shop shop = lockedShop(storeId);
    expire(shop);
    var args =
        new ArrayList<Object>(
            List.of(
                storeId,
                from.atStartOfDay(SEOUL).toOffsetDateTime(),
                to.plusDays(1).atStartOfDay(SEOUL).toOffsetDateTime(),
                now()));
    String where =
        "where e.storeId=?1 and e.start>=?2 and e.start<?3 and e.start>?4 and e.status='AVAILABLE'"
            + " and e.serviceId in (select s.id from StoreService s where s.active='Y')";
    if (serviceId != null) {
      service(storeId, serviceId);
      args.add(serviceId);
      where += " and e.serviceId=?5";
    }
    var page =
        page(
            Slot.class,
            where,
            args,
            "start",
            false,
            cursor,
            "slots:" + storeId + ":" + from + ":" + to + ":" + serviceId,
            limit,
            s -> s.start.toString(),
            s -> s.id);
    return new ReservationSlotListResponse(
        page.items.stream().map(this::slotDto).toList(), page.page);
  }

  public ReservationSlotResponse getSlot(String storeId, String slotId) {
    Shop shop = lockedShop(storeId);
    expire(shop);
    return slotDto(slot(storeId, slotId));
  }

  public ReservationResponse reserve(Actor a, String key, CreateReservationRequest r) {
    return once(
        a,
        "RESERVE",
        key,
        r,
        201,
        ReservationResponse.class,
        () -> {
          text(r.slotId(), 64);
          price(r.expectedPrice());
          Slot initial = db.find(Slot.class, r.slotId());
          require(initial != null, 404, "SLOT_NOT_FOUND");
          Shop shop = lockedShop(initial.storeId);
          Slot s = db.lock(Slot.class, r.slotId());
          require(s.start.isAfter(now()), 409, "SLOT_EXPIRED");
          require("AVAILABLE".equals(s.status), 409, "SLOT_UNAVAILABLE");
          StoreService service = service(s.storeId, s.serviceId);
          require("Y".equals(service.active), 409, "SERVICE_INACTIVE");
          version(r.expectedSlotVersion(), s.version);
          require(r.expectedPrice().equals(s.payable), 409, "PRICE_CHANGED");
          require(
              db.query(
                      Reservation.class,
                      "where e.status='CONFIRMED' and e.start<?1 and e.end>?2 and (e.userId=?3 or"
                          + " e.storeId=?4)",
                      s.end,
                      s.start,
                      a.id(),
                      s.storeId)
                  .isEmpty(),
              409,
              "RESERVATION_TIME_CONFLICT");
          String reservationId = id("rsv_");
          if (r.waitlistId() != null)
            waitlists.fulfill(
                r.waitlistId(),
                new WaitlistReservationService.Candidate(
                    a.id(),
                    s.storeId,
                    s.serviceId,
                    shop.category,
                    s.start,
                    s.end,
                    s.payable,
                    shop.latitude,
                    shop.longitude),
                now(),
                reservationId);
          Reservation v = new Reservation();
          v.id = reservationId;
          v.userId = a.id();
          v.customerName = a.displayName();
          v.storeId = s.storeId;
          v.slotId = s.id;
          v.waitlistId = r.waitlistId();
          v.serviceId = s.serviceId;
          v.serviceName = service.name;
          v.duration = (int) ChronoUnit.MINUTES.between(s.start, s.end);
          v.start = s.start;
          v.end = s.end;
          v.originalPrice = s.originalPrice;
          v.payable = s.payable;
          v.status = "CONFIRMED";
          v.version = 1L;
          v.createdAt = now();
          db.save(v);
          s.status = "BOOKED";
          s.version++;
          event(s, "BOOKED");
          return reservationDto(v, false);
        });
  }

  /** 제안은 공급이 아니다. 작성 단계에서는 현재 값만 검증하고 예약 원장을 변경하지 않는다. */
  public void validateProposal(ProposalCommand r) {
    proposalInput(r);
    var customer = users.get(r.userId());
    require(!customer.id().equals(r.ownerId()), 403, "RESERVATION_FORBIDDEN");
    proposalService(r, shop(r.storeId()));
  }

  private void proposalInput(ProposalCommand r) {
    require(r != null, 400, "INVALID_INPUT");
    text(r.userId(), 64);
    text(r.ownerId(), 64);
    text(r.proposalId(), 64);
    text(r.storeId(), 64);
    text(r.serviceId(), 64);
    text(r.serviceDescription(), 1000);
    price(r.price());
    require(
        r.durationMinutes() != null
            && r.durationMinutes() >= 15
            && r.durationMinutes() <= 480
            && r.durationMinutes() % 5 == 0,
        400,
        "INVALID_DURATION");
    require(r.startAt() != null, 400, "INVALID_TIME");
    require(r.startAt().getYear() >= 1 && r.startAt().getYear() <= 9999, 400, "INVALID_TIME");
    require(
        normalize(r.startAt()).plusMinutes(r.durationMinutes()).getYear() <= 9999,
        400,
        "INVALID_TIME");
    if (r.waitlistId() != null) text(r.waitlistId(), 64);
  }

  private StoreService proposalService(ProposalCommand r, Shop shop) {
    var owner = users.get(r.ownerId());
    ownerRole(owner);
    require(shop.ownerId.equals(owner.id()), 403, "STORE_FORBIDDEN");
    StoreService service = service(shop.id, r.serviceId());
    require("Y".equals(service.active), 409, "SERVICE_INACTIVE");
    OffsetDateTime start = normalize(r.startAt());
    require(start.isAfter(now()), 409, "SLOT_EXPIRED");
    inHours(shop, start, start.plusMinutes(r.durationMinutes()));
    return service;
  }

  public ReservationResponse reserveProposal(ProposalCommand r) {
    proposalInput(r);
    Actor customer = users.get(r.userId());
    require(!customer.id().equals(r.ownerId()), 403, "RESERVATION_FORBIDDEN");
    return once(
        customer,
        "RESERVE_PROPOSAL:" + r.proposalId(),
        r.idempotencyKey(),
        r,
        201,
        ReservationResponse.class,
        () -> {
          // 채팅방 잠금 뒤 다른 사용자의 계정 잠금을 잡지 않는다. 고객 → 매장 순서를 유지한다.
          Shop shop = lockedShop(r.storeId());
          StoreService service = proposalService(r, shop);
          OffsetDateTime start = normalize(r.startAt()),
              end = start.plusMinutes(r.durationMinutes());
          require(
              db.query(
                      Reservation.class,
                      "where e.status='CONFIRMED' and e.start<?1 and e.end>?2 and (e.userId=?3 or"
                          + " e.storeId=?4)",
                      end,
                      start,
                      customer.id(),
                      shop.id)
                  .isEmpty(),
              409,
              "RESERVATION_TIME_CONFLICT");
          Slot matching =
              db
                  .query(
                      Slot.class,
                      "where e.storeId=?1 and e.serviceId=?2 and e.status='AVAILABLE' and"
                          + " e.start=?3 and e.end=?4",
                      shop.id,
                      service.id,
                      start,
                      end)
                  .stream()
                  .findFirst()
                  .orElse(null);
          supplyConflict(shop.id, start, end, matching == null ? null : matching.id);
          Slot slot;
          if (matching == null) {
            slot = new Slot();
            slot.id = id("slot_");
            slot.storeId = shop.id;
            slot.serviceId = service.id;
            slot.start = start;
            slot.end = end;
            slot.availableFrom = start;
            slot.availableUntil = end;
            slot.originalPrice = r.price();
            slot.payable = r.price();
            slot.source = "REGULAR";
            slot.status = "BOOKED";
            slot.version = 1L;
            slot.createdAt = now();
            db.save(slot);
            // JPA는 문자열 FK 관계를 모른다. 예약 INSERT 전에 원슬롯을 같은 트랜잭션에 저장한다.
            db.flush();
          } else {
            slot = db.lock(Slot.class, matching.id);
            require("AVAILABLE".equals(slot.status), 409, "SLOT_UNAVAILABLE");
            slot.status = "BOOKED";
            slot.version++;
          }
          String reservationId = id("rsv_");
          if (r.waitlistId() != null)
            waitlists.fulfill(
                r.waitlistId(),
                new WaitlistReservationService.Candidate(
                    customer.id(),
                    shop.id,
                    service.id,
                    shop.category,
                    start,
                    end,
                    r.price(),
                    shop.latitude,
                    shop.longitude),
                now(),
                reservationId);
          Reservation reservation = new Reservation();
          reservation.id = reservationId;
          reservation.userId = customer.id();
          reservation.customerName = customer.displayName();
          reservation.storeId = shop.id;
          reservation.slotId = slot.id;
          reservation.waitlistId = r.waitlistId();
          reservation.serviceId = service.id;
          reservation.serviceName = service.name;
          reservation.serviceDescription = r.serviceDescription();
          reservation.duration = r.durationMinutes();
          reservation.start = start;
          reservation.end = end;
          // 추가 옵션까지 포함한 사장님 제안 총액을 정가와 예약 금액의 스냅샷으로 보존한다.
          reservation.originalPrice = r.price();
          reservation.payable = r.price();
          reservation.status = "CONFIRMED";
          reservation.version = 1L;
          reservation.createdAt = now();
          db.save(reservation);
          event(slot, "BOOKED");
          return reservationDto(reservation, false);
        });
  }

  public ReservationResponse getReservation(Actor a, String id) {
    Reservation r = reservation(id);
    access(a, r);
    return reservationDto(r, "OWNER".equals(a.role()) && shop(r.storeId).ownerId.equals(a.id()));
  }

  public OwnerReservationResponse ownerReservation(Actor a, String storeId, String id) {
    owned(a, storeId, false);
    Reservation r = reservation(id);
    require(r.storeId.equals(storeId), 404, "RESERVATION_NOT_FOUND");
    return ownerDto(r);
  }

  public ReservationListResponse myReservations(
      Actor a, String scope, String cursor, Integer limit) {
    require(
        Set.of("UPCOMING", "PAST", "CANCELED").contains(scope == null ? "" : scope),
        400,
        "INVALID_SCOPE");
    OffsetDateTime moment = now();
    var totals = db.reservationCounts(a.id(), moment);
    var counts =
        new ReservationListResponse.Counts(
            totals.upcoming(),
            totals.total() - totals.upcoming() - totals.canceled(),
            totals.canceled());
    String filter =
        switch (scope) {
          case "UPCOMING" -> "e.status='CONFIRMED' and e.end>?2";
          case "PAST" -> "e.status<>'CANCELED' and (e.status<>'CONFIRMED' or e.end<=?2)";
          default -> "e.status='CANCELED'";
        };
    var args = new ArrayList<Object>(List.of(a.id()));
    if (!scope.equals("CANCELED")) args.add(moment);
    var page =
        page(
            Reservation.class,
            "where e.userId=?1 and (" + filter + ")",
            args,
            "start",
            true,
            cursor,
            "reservations:" + a.id() + ":" + scope,
            limit,
            r -> r.start.toString(),
            r -> r.id);
    return new ReservationListResponse(
        page.items.stream().map(r -> reservationDto(r, false)).toList(), page.page, counts);
  }

  public CancelReservationResponse cancel(
      Actor a, String id, String key, CancelReservationRequest r) {
    return once(
        a,
        "CANCEL:" + id,
        key,
        r,
        200,
        CancelReservationResponse.class,
        () -> {
          Reservation initial = reservation(id);
          access(a, initial);
          lockedShop(initial.storeId);
          Reservation v = db.lock(Reservation.class, id);
          version(r.expectedVersion(), v.version);
          require("CONFIRMED".equals(v.status), 409, "RESERVATION_STATE_CONFLICT");
          require(
              Set.of("CUSTOMER_REQUEST", "STORE_REQUEST", "OTHER")
                  .contains(r.reasonCode() == null ? "" : r.reasonCode()),
              400,
              "INVALID_CANCEL_REASON");
          if ("OTHER".equals(r.reasonCode())) text(r.reason(), 300);
          else
            require(r.reason() == null || r.reason().length() <= 300, 400, "INVALID_CANCEL_REASON");
          require(
              !"STORE_REQUEST".equals(r.reasonCode())
                  || ("OWNER".equals(a.role()) && shop(v.storeId).ownerId.equals(a.id())),
              403,
              "OWNER_REQUIRED");
          v.status = "CANCELED";
          v.cancelReasonCode = r.reasonCode();
          v.cancelReason = r.reason();
          v.canceledAt = now();
          v.version++;
          Slot original = slot(v.storeId, v.slotId);
          if ("REGULAR".equals(original.source)) original.status = "CLOSED";
          original.version++;
          event(original, "CANCELED");
          Slot vacancy = vacancy(v, "CANCELLATION");
          return new CancelReservationResponse(
              reservationDto(v, !v.userId.equals(a.id())),
              vacancy == null ? null : vacancyDto(vacancy));
        });
  }

  private Slot vacancy(Reservation r, String reason) {
    if (!r.end.isAfter(now())) return null;
    var existing = db.query(Slot.class, "where e.sourceReservationId=?1", r.id);
    if (!existing.isEmpty()) return existing.getFirst();
    Slot s = new Slot();
    s.id = id("vac_");
    s.storeId = r.storeId;
    s.serviceId = r.serviceId;
    s.originalServiceId = r.serviceId;
    s.vacancyOriginalPrice = r.originalPrice;
    s.start = r.start.isAfter(now()) ? r.start : now();
    s.end = r.end;
    s.availableFrom = s.start;
    s.availableUntil = r.end;
    s.originalPrice = r.originalPrice;
    s.payable = r.payable;
    s.status = "DRAFT";
    s.source = reason;
    s.sourceReservationId = r.id;
    s.version = 1L;
    s.createdAt = now();
    db.save(s);
    return s;
  }

  public UpdateReservationStatusResponse updateStatus(
      Actor a, String storeId, String id, String key, UpdateReservationStatusRequest r) {
    ownerRole(a);
    return once(
        a,
        "STATUS:" + storeId + ":" + id,
        key,
        r,
        200,
        UpdateReservationStatusResponse.class,
        () -> {
          owned(a, storeId, true);
          Reservation v = db.lock(Reservation.class, id);
          require(v != null && v.storeId.equals(storeId), 404, "RESERVATION_NOT_FOUND");
          version(r.expectedVersion(), v.version);
          require("CONFIRMED".equals(v.status), 409, "RESERVATION_STATE_CONFLICT");
          require(
              Set.of("COMPLETED", "NO_SHOW").contains(r.status() == null ? "" : r.status()),
              400,
              "INVALID_STATUS");
          require(
              "COMPLETED".equals(r.status())
                  ? !now().isBefore(v.end)
                  : !now().isBefore(v.start.plusMinutes(10)),
              409,
              "STATUS_TOO_EARLY");
          v.status = r.status();
          v.version++;
          Slot s = "NO_SHOW".equals(v.status) ? vacancy(v, "NO_SHOW") : null;
          Slot original = slot(storeId, v.slotId);
          original.version++;
          event(original, r.status());
          return new UpdateReservationStatusResponse(ownerDto(v), s == null ? null : vacancyDto(s));
        });
  }

  private String vacancyStatus(Slot s) {
    if ("BOOKED".equals(s.status)) return "FILLED";
    if (expired(s) || "EXPIRED".equals(s.status)) return "EXPIRED";
    return switch (s.status) {
      case "AVAILABLE" -> "PUBLISHED";
      case "CLOSED" -> "WITHDRAWN";
      default -> "DRAFT";
    };
  }

  public VacancyListResponse vacancies(
      Actor a, String storeId, String status, String cursor, Integer limit) {
    Shop shop = owned(a, storeId, true);
    expire(shop);
    require(
        status == null
            || Set.of("DRAFT", "PUBLISHED", "FILLED", "WITHDRAWN", "EXPIRED").contains(status),
        400,
        "INVALID_STATUS");
    String base = "where e.storeId=?1 and e.source<>'REGULAR'";
    var counts =
        new VacancyListResponse.Counts(
            db.count(Slot.class, base + " and e.status in ('DRAFT','CLOSED')", storeId),
            db.count(Slot.class, base + " and e.status='AVAILABLE'", storeId),
            db.count(Slot.class, base + " and e.status='BOOKED'", storeId));
    var args = new ArrayList<Object>(List.of(storeId));
    String where = "where e.storeId=?1 and e.source<>'REGULAR'";
    if (status != null) {
      args.add(
          switch (status) {
            case "PUBLISHED" -> "AVAILABLE";
            case "FILLED" -> "BOOKED";
            case "WITHDRAWN" -> "CLOSED";
            default -> status;
          });
      where += " and e.status=?2";
    }
    var page =
        page(
            Slot.class,
            where,
            args,
            "createdAt",
            true,
            cursor,
            "vacancies:" + storeId + ":" + status,
            limit,
            s -> s.createdAt.toString(),
            s -> s.id);
    return new VacancyListResponse(
        page.items.stream().map(this::vacancyDto).toList(), page.page, counts);
  }

  private Slot vacancySlot(String storeId, String id) {
    Slot s = slot(storeId, id);
    require(!"REGULAR".equals(s.source), 404, "VACANCY_NOT_FOUND");
    return s;
  }

  public PublishVacancyResponse publish(
      Actor a, String storeId, String id, String key, PublishVacancyRequest r) {
    ownerRole(a);
    return once(
        a,
        "PUBLISH:" + storeId + ":" + id,
        key,
        r,
        200,
        PublishVacancyResponse.class,
        () -> {
          Shop shop = owned(a, storeId, true);
          expire(shop);
          Slot s = vacancySlot(storeId, id);
          version(r.expectedVersion(), s.version);
          require(!"BOOKED".equals(s.status), 409, "VACANCY_ALREADY_FILLED");
          require(Set.of("DRAFT", "CLOSED").contains(s.status), 409, "VACANCY_STATE_CONFLICT");
          StoreService service = service(storeId, r.serviceId());
          require("Y".equals(service.active), 409, "SERVICE_INACTIVE");
          OffsetDateTime start = normalize(r.startAt()), end = start.plusMinutes(service.duration);
          require(
              start.isAfter(now())
                  && !start.isBefore(s.availableFrom)
                  && !end.isAfter(s.availableUntil),
              409,
              "VACANCY_TIME_MISMATCH");
          supplyConflict(storeId, start, end, s.id);
          require(r.discount() != null && r.discount().value() != null, 400, "INVALID_DISCOUNT");
          long value = r.discount().value(), payable;
          if ("PERCENT".equals(r.discount().type())) {
            require(value >= 0 && value <= 100, 400, "INVALID_DISCOUNT");
            payable = service.price * (100 - value) / 100;
          } else {
            require(
                "FIXED_PRICE".equals(r.discount().type()) && value >= 0 && value <= service.price,
                400,
                "INVALID_DISCOUNT");
            payable = value;
          }
          s.serviceId = service.id;
          s.start = start;
          s.end = end;
          s.originalPrice = service.price;
          s.payable = payable;
          s.discountType = r.discount().type();
          s.discountValue = value;
          s.status = "AVAILABLE";
          s.publicationVersion = s.publicationVersion == null ? 1 : s.publicationVersion + 1;
          s.version++;
          s.publishedAt = now();
          event(s, "PUBLISHED");
          return new PublishVacancyResponse(vacancyDto(s), ownerSlotDto(s), "QUEUED");
        });
  }

  public WithdrawVacancyResponse withdraw(
      Actor a, String storeId, String id, String key, WithdrawVacancyRequest r) {
    ownerRole(a);
    return once(
        a,
        "WITHDRAW:" + storeId + ":" + id,
        key,
        r,
        200,
        WithdrawVacancyResponse.class,
        () -> {
          Shop shop = owned(a, storeId, true);
          expire(shop);
          Slot s = vacancySlot(storeId, id);
          require(!"BOOKED".equals(s.status), 409, "VACANCY_ALREADY_FILLED");
          version(r.expectedVersion(), s.version);
          require("AVAILABLE".equals(s.status), 409, "VACANCY_STATE_CONFLICT");
          s.status = "CLOSED";
          s.version++;
          event(s, "WITHDRAWN");
          return new WithdrawVacancyResponse(vacancyDto(s), ownerSlotDto(s));
        });
  }

  public TimetableResponse timetable(Actor a, String storeId, LocalDate date) {
    Shop shop = owned(a, storeId, true);
    expire(shop);
    require(date != null, 400, "INVALID_DATE");
    OffsetDateTime start = date.atStartOfDay(SEOUL).toOffsetDateTime(), end = start.plusDays(1);
    var reservations =
        db.limited(
            Reservation.class,
            "where e.storeId=?1 and e.start<?2 and e.end>?3 order by e.start,e.id",
            501,
            storeId,
            end,
            start);
    var slots =
        db.limited(
            Slot.class,
            "where e.storeId=?1 and e.availableFrom<?2 and e.availableUntil>?3 and"
                + " (e.status='AVAILABLE' or e.source<>'REGULAR') order by e.start,e.id",
            501,
            storeId,
            end,
            start);
    require(reservations.size() + slots.size() <= 500, 409, "TIMETABLE_LIMIT_EXCEEDED");
    return new TimetableResponse(
        date,
        "Asia/Seoul",
        new TimetableResponse.Summary(
            reservations.size(),
            (int)
                reservations.stream()
                    .filter(r -> "CONFIRMED".equals(r.status) && r.end.isAfter(now()))
                    .count()),
        List.of(new TimetableResponse.ResourceItem(resource(storeId), "기본 예약 자원")),
        reservations.stream().map(this::ownerDto).toList(),
        slots.stream().filter(s -> "AVAILABLE".equals(s.status)).map(this::slotDto).toList(),
        slots.stream().filter(s -> !"REGULAR".equals(s.source)).map(this::vacancyDto).toList());
  }

  private StoreDetailResponse shopDto(Shop s) {
    return new StoreDetailResponse(
        s.id,
        s.name,
        s.category,
        s.description,
        s.address,
        new StoreDetailResponse.Location(s.latitude, s.longitude),
        s.imageUrl,
        new StoreDetailResponse.Rating(s.ratingAverage, s.ratingCount),
        s.timezone,
        hours(s).stream()
            .map(
                h ->
                    new StoreDetailResponse.OpeningHourItem(
                        h.dayOfWeek(), h.opensAt(), h.closesAt(), h.closed()))
            .toList(),
        s.version);
  }

  private StoreServiceResponse serviceDto(Shop shop, StoreService s) {
    return new StoreServiceResponse(
        s.id,
        s.storeId,
        shop.category,
        s.name,
        s.duration,
        s.price,
        "KRW",
        "Y".equals(s.active),
        s.version);
  }

  private ReservationSlotResponse slotDto(Slot s) {
    Integer percent = null;
    if ("PERCENT".equals(s.discountType)) percent = s.discountValue.intValue();
    else if ("REGULAR".equals(s.source) && s.originalPrice.equals(s.payable)) percent = 0;
    String status =
        expired(s)
            ? "EXPIRED"
            : switch (s.status) {
              case "DRAFT", "CLOSED" -> "BLOCKED";
              default -> s.status;
            };
    boolean active = "Y".equals(service(s.storeId, s.serviceId).active);
    boolean bookable = "AVAILABLE".equals(status) && active;
    return new ReservationSlotResponse(
        s.id,
        s.storeId,
        s.serviceId,
        null,
        s.start,
        s.end,
        status,
        bookable,
        bookable ? null : ("AVAILABLE".equals(status) ? "SERVICE_INACTIVE" : status),
        "REGULAR".equals(s.source) ? "REGULAR" : "VACANCY",
        "REGULAR".equals(s.source) ? null : s.id,
        s.publicationVersion,
        new ReservationSlotResponse.Price(s.originalPrice, s.payable, percent, "KRW"),
        s.version);
  }

  private ReservationSlotResponse ownerSlotDto(Slot s) {
    var r = slotDto(s);
    return new ReservationSlotResponse(
        r.id(),
        r.storeId(),
        r.serviceId(),
        resource(s.storeId),
        r.startAt(),
        r.endAt(),
        r.status(),
        r.bookable(),
        r.unavailableReason(),
        r.source(),
        r.vacancyId(),
        r.publicationVersion(),
        r.price(),
        r.version());
  }

  private VacancyResponse vacancyDto(Slot s) {
    return new VacancyResponse(
        s.id,
        s.storeId,
        s.sourceReservationId,
        s.source,
        vacancyStatus(s),
        resource(s.storeId),
        s.availableFrom,
        s.availableUntil,
        s.originalServiceId,
        s.vacancyOriginalPrice,
        s.publicationVersion == null ? null : s.id,
        s.publishedAt,
        s.createdAt,
        s.version);
  }

  private List<String> actions(Reservation r, boolean owner) {
    var result = new ArrayList<String>(List.of("VIEW_STORE", "OPEN_CHAT"));
    if (!"CONFIRMED".equals(r.status)) return result;
    result.add("CANCEL");
    if (owner && !now().isBefore(r.end)) result.add("COMPLETE");
    if (owner && !now().isBefore(r.start.plusMinutes(10))) result.add("NO_SHOW");
    return result;
  }

  private ReservationResponse reservationDto(Reservation r, boolean owner) {
    Shop s = shop(r.storeId);
    return new ReservationResponse(
        r.id,
        new ReservationResponse.Store(s.id, s.name, s.imageUrl),
        new ReservationResponse.Service(
            r.serviceId, r.serviceName, r.duration, r.serviceDescription),
        r.slotId,
        r.status,
        r.start,
        r.end,
        new ReservationResponse.Price(r.originalPrice, r.payable, "KRW"),
        r.waitlistId,
        r.cancelReasonCode,
        r.canceledAt,
        r.createdAt,
        r.version,
        actions(r, owner),
        owner ? new ReservationResponse.CustomerSummary(r.userId, r.customerName) : null,
        owner ? resource(r.storeId) : null);
  }

  private OwnerReservationResponse ownerDto(Reservation r) {
    Shop s = shop(r.storeId);
    return new OwnerReservationResponse(
        r.id,
        new OwnerReservationResponse.Store(s.id, s.name, s.imageUrl),
        new OwnerReservationResponse.Service(
            r.serviceId, r.serviceName, r.duration, r.serviceDescription),
        r.slotId,
        r.status,
        r.start,
        r.end,
        new OwnerReservationResponse.Price(r.originalPrice, r.payable, "KRW"),
        r.waitlistId,
        r.cancelReasonCode,
        r.canceledAt,
        r.createdAt,
        r.version,
        actions(r, true),
        new OwnerReservationResponse.Customer(r.userId, r.customerName),
        resource(r.storeId));
  }
}
