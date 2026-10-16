package com.bintime.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.bintime.store.service.StoreApplicationService;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:store-api;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "bintime.auth.local-enabled=true",
      "bintime.store.expiration-enabled=false",
      "search.scheduling-enabled=false"
    })
@ActiveProfiles("test")
class StoreApiTests {
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired StoreApplicationService stores;
  @Autowired com.bintime.store.contract.SearchStoreCatalog catalog;
  @Autowired com.bintime.search.service.WaitlistService waitlists;
  @Autowired com.bintime.search.service.SearchIndexService searchIndex;
  @Autowired com.bintime.search.service.MatchService matchService;
  @Autowired com.bintime.store.repository.StoreRepository storeRepository;
  @Autowired org.springframework.transaction.support.TransactionTemplate transactions;

  @MockitoBean(name = "storeClock")
  Clock clock;

  @MockitoBean com.bintime.search.config.SearchSchemaVerifier unrelatedSearchSchemaVerifier;

  private final HttpClient client = HttpClient.newHttpClient();
  private static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
  private static final String BASE = "/api/v1";

  @org.springframework.test.context.DynamicPropertySource
  static void oracleProperties(org.springframework.test.context.DynamicPropertyRegistry props) {
    if (System.getenv("STORE_TEST_DB_URL") != null) {
      if (!System.getenv("STORE_TEST_DB_USER").matches("BT_STORE_[A-F0-9]{12}")
          || !System.getenv("STORE_TEST_DB_URL")
              .matches("jdbc:oracle:thin:@//localhost:[0-9]+/FREEPDB1"))
        throw new IllegalArgumentException(
            "Oracle tests require an isolated BT_STORE_ schema on localhost");
      props.add("spring.datasource.url", () -> System.getenv("STORE_TEST_DB_URL"));
      props.add("spring.datasource.username", () -> System.getenv("STORE_TEST_DB_USER"));
      props.add("spring.datasource.password", () -> System.getenv("STORE_TEST_DB_PASSWORD"));
      props.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
      props.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }
  }

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    when(clock.millis()).thenReturn(NOW.toEpochMilli());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    for (String table : List.of("search_work", "search_intent", "search_offer"))
      db.update("delete from " + table);
    db.update("delete from store_events");
    // 슬롯과 원예약의 순환 FK를 테스트 전용 스키마 안에서만 해제한 뒤 정리한다.
    db.update(
        "update store_slots set"
            + " source_type='REGULAR',source_reservation_id=null,publication_version=null");
    for (String table :
        List.of(
            "store_idempotency",
            "store_customer_locks",
            "store_reservations",
            "store_slots",
            "search_waitlists",
            "store_services",
            "store_shops",
            "user_accounts")) db.update("delete from " + table);
    for (String id : List.of("owner", "otherOwner", "alice", "bob"))
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,?)",
          id,
          id,
          id.contains("wner") ? "OWNER" : "CUSTOMER");
    // H2 uses a generated column for Oracle's function-based uniqueness constraint.
    if (System.getenv("STORE_TEST_DB_URL") == null) {
      db.execute(
          "alter table store_reservations add column if not exists confirmed_slot varchar(64)"
              + " generated always as (case when status_code='CONFIRMED' then slot_id else null"
              + " end)");
      db.execute(
          "create unique index if not exists ux_test_confirmed on"
              + " store_reservations(confirmed_slot)");
    }
  }

  private record Result(int status, JsonNode body) {
    JsonNode data() {
      return body.at("/success/data");
    }

    String code() {
      return body.at("/error/code").asText();
    }
  }

  private Result call(String method, String path, String actor, String key, Object body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Content-Type", "application/json");
    if (actor != null) b.header("X-Local-User-Id", actor);
    if (key != null) b.header("Idempotency-Key", key);
    b.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(
                body instanceof String ? (String) body : json.writeValueAsString(body)));
    var response = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    return new Result(response.statusCode(), json.readTree(response.body()));
  }

  private JsonNode ok(String method, String path, String actor, String key, Object body, int status)
      throws Exception {
    Result r = call(method, path, actor, key, body);
    assertThat(r.status).as(r.body.toString()).isEqualTo(status);
    assertThat(r.body.at("/meta/path").asText()).isEqualTo(path.split("\\?")[0]);
    return r.data();
  }

  private String shop(String owner) throws Exception {
    return ok(
            "POST", BASE + "/owner/stores", owner, UUID.randomUUID().toString(), shopRequest(), 201)
        .path("id")
        .asText();
  }

  private Map<String, Object> shopRequest() {
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    Map.of(
                        "dayOfWeek", day, "opensAt", "09:00", "closesAt", "22:00", "closed", false))
            .toList();
    return Map.of(
        "name",
        "매장",
        "category",
        "NAIL",
        "address",
        "서울",
        "location",
        Map.of("latitude", 37.5, "longitude", 127.0),
        "openingHours",
        hours);
  }

  @Test
  void customerBecomesOwnerOnStoreCreationAndCanStillBookAsCustomer() throws Exception {
    String own = shop("alice");
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("OWNER");
    ok("GET", ownerPath(own) + "/timetable?date=2026-10-17", "alice", null, null, 200);
    String other = shop("owner");
    String service = service(other, 60);
    JsonNode slot = slot(other, service, "2026-10-17T10:00:00+09:00");
    assertThat(reserve(slot, "alice").path("status").asText()).isEqualTo("CONFIRMED");
    assertThat(
            call("GET", ownerPath(other) + "/timetable?date=2026-10-17", "alice", null, null)
                .status)
        .isEqualTo(403);
  }

  @Test
  void failedRegistrationAndTransactionRollbackDoNotGrantOwner() throws Exception {
    var invalid = new HashMap<>(shopRequest());
    invalid.put("category", "INVALID");
    assertThat(call("POST", BASE + "/owner/stores", "alice", "retry-store", invalid).status)
        .isEqualTo(400);
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("CUSTOMER");
    var request = json.convertValue(shopRequest(), com.bintime.store.dto.CreateStoreRequest.class);
    assertThatThrownBy(
            () ->
                transactions.executeWithoutResult(
                    status -> {
                      stores.createStore(
                          new com.bintime.common.auth.Actor("alice", "CUSTOMER", "alice"),
                          "rollback",
                          request);
                      throw new IllegalStateException("rollback after registration");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("CUSTOMER");
    assertThat(
            db.queryForObject(
                "select count(*) from store_shops where owner_user_id='alice'", Integer.class))
        .isZero();
    var first = ok("POST", BASE + "/owner/stores", "alice", "retry-store", shopRequest(), 201);
    var replay = ok("POST", BASE + "/owner/stores", "alice", "retry-store", shopRequest(), 201);
    assertThat(replay).isEqualTo(first);
    assertThat(
            db.queryForObject(
                "select count(*) from store_shops where owner_user_id='alice'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void concurrentFirstRegistrationReplaysOneStore() throws Exception {
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first =
          pool.submit(
              () ->
                  call("POST", BASE + "/owner/stores", "alice", "concurrent-store", shopRequest()));
      var second =
          pool.submit(
              () ->
                  call("POST", BASE + "/owner/stores", "alice", "concurrent-store", shopRequest()));
      Result a = first.get(10, TimeUnit.SECONDS);
      Result b = second.get(10, TimeUnit.SECONDS);
      assertThat(a.status).isEqualTo(201);
      assertThat(b.status).isEqualTo(201);
      assertThat(a.data()).isEqualTo(b.data());
    }
    assertThat(
            db.queryForObject(
                "select count(*) from store_shops where owner_user_id='alice'", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select role_code from user_accounts where user_id='alice'", String.class))
        .isEqualTo("OWNER");
  }

  private String service(String shop, int duration) throws Exception {
    return ok(
            "POST",
            BASE + "/owner/stores/" + shop + "/services",
            "owner",
            UUID.randomUUID().toString(),
            Map.of("name", "젤 네일", "durationMinutes", duration, "basePrice", 50001, "active", true),
            201)
        .path("id")
        .asText();
  }

  private JsonNode slot(String shop, String service, String time) throws Exception {
    return ok(
        "POST",
        BASE + "/owner/stores/" + shop + "/slots",
        "owner",
        UUID.randomUUID().toString(),
        Map.of("serviceId", service, "startAt", time),
        201);
  }

  private Map<String, Object> booking(JsonNode slot) {
    return Map.of(
        "slotId",
        slot.path("id").asText(),
        "expectedPrice",
        slot.at("/price/payable").asLong(),
        "expectedSlotVersion",
        slot.path("version").asLong());
  }

  private JsonNode reserve(JsonNode slot, String user) throws Exception {
    return ok(
        "POST", BASE + "/reservations", user, UUID.randomUUID().toString(), booking(slot), 201);
  }

  private String ownerPath(String shop) {
    return BASE + "/owner/stores/" + shop;
  }

  private com.bintime.search.dto.WaitlistConditions conditions(String shop, String service) {
    return new com.bintime.search.dto.WaitlistConditions(
        shop,
        "NAIL",
        service,
        OffsetDateTime.parse("2026-10-16T11:00:00+09:00"),
        OffsetDateTime.parse("2026-10-16T18:00:00+09:00"),
        shop == null ? new com.bintime.search.dto.WaitlistConditions.Location(37.5, 127.0) : null,
        shop == null ? 1000 : null,
        null,
        List.of());
  }

  private com.bintime.store.contract.SearchStoreCatalog.StoreDocument document(String shop) {
    return catalog.export(null, 100).items().stream()
        .filter(s -> s.id().equals(shop))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void realCatalogValidatesReferencesWithoutRequiringExistingSupply() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60), other = shop("owner");
    catalog.validate(conditions(shop, svc));
    assertThat(catalog.available(conditions(shop, svc), null, 20).items()).isEmpty();
    var wait =
        waitlists.create(
            "alice",
            "catalog-wait",
            new com.bintime.search.dto.CreateWaitlistRequest(
                "MANUAL", conditions(shop, svc), true));
    assertThat(wait.status()).isEqualTo("ACTIVE");
    var detail = waitlists.get("alice", wait.id());
    assertThat(detail.store().id()).isEqualTo(shop);
    assertThat(detail.service().id()).isEqualTo(svc);
    assertThat(catalog.describe(shop, svc).service().name()).isEqualTo("젤 네일");
    assertThat(catalog.describe(null, svc).store().id()).isEqualTo(shop);
    assertThat(catalog.describe(null, null).store()).isNull();
    assertThatThrownBy(() -> catalog.validate(conditions("missing", null)))
        .hasMessage("STORE_NOT_FOUND");
    assertThatThrownBy(() -> catalog.validate(conditions(shop, "missing")))
        .hasMessage("SERVICE_NOT_FOUND");
    assertThatThrownBy(() -> catalog.validate(conditions(other, svc)))
        .hasMessage("SERVICE_STORE_MISMATCH");
    db.update("update store_shops set category_code='HAIR' where store_id=?", shop);
    assertThatThrownBy(() -> catalog.validate(conditions(shop, svc)))
        .hasMessage("STORE_CATEGORY_MISMATCH");
    db.update("update store_shops set category_code='NAIL' where store_id=?", shop);
    ok(
        "PATCH",
        ownerPath(shop) + "/services/" + svc,
        "owner",
        null,
        Map.of("active", false, "expectedVersion", 1),
        200);
    assertThatThrownBy(() -> catalog.validate(conditions(shop, svc)))
        .hasMessage("SERVICE_INACTIVE");
    assertThat(catalog.describe(shop, svc).service().id()).isEqualTo(svc);
  }

  @Test
  void catalogRevalidatesBookingDraftPublicationWithdrawalAndExpiry() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode slot = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    String original = slot.path("id").asText();
    assertThat(catalog.revalidate(List.of(original, original, "missing"))).hasSize(1);
    long version = document(shop).version();
    JsonNode reservation = reserve(slot, "alice");
    assertThat(catalog.revalidate(List.of(original))).isEmpty();
    assertThat(document(shop).version()).isGreaterThan(version);
    JsonNode vacancy = cancel(reservation).path("vacancy");
    String id = vacancy.path("id").asText();
    assertThat(catalog.revalidate(List.of(original, id))).isEmpty();
    JsonNode published = publish(shop, vacancy, svc, "2026-10-16T12:00:00+09:00", "catalog-pub");
    var supply = catalog.revalidate(List.of(id)).getFirst();
    assertThat(supply.source()).isEqualTo("VACANCY");
    assertThat(supply.payablePrice()).isEqualTo(35000);
    assertThat(supply.publicationVersion()).isEqualTo(1);
    JsonNode withdrawn =
        ok(
            "POST",
            ownerPath(shop) + "/vacancies/" + id + "/withdraw",
            "owner",
            "catalog-with",
            Map.of("expectedVersion", published.at("/vacancy/version").asLong()),
            200);
    assertThat(catalog.revalidate(List.of(id))).isEmpty();
    publish(shop, withdrawn.path("vacancy"), svc, "2026-10-16T12:00:00+09:00", "catalog-repub");
    assertThat(catalog.revalidate(List.of(id)).getFirst().publicationVersion()).isEqualTo(2);
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:00:00Z"));
    assertThat(catalog.revalidate(List.of(id))).isEmpty();
    assertThat(
            db.queryForObject(
                "select status_code from store_slots where slot_id=?", String.class, id))
        .isEqualTo("AVAILABLE");
    long beforeExpiry = document(shop).version();
    stores.expireStore(shop);
    assertThat(document(shop).version()).isGreaterThan(beforeExpiry);
    assertThat(document(shop).slots()).isEmpty();
  }

  @Test
  void catalogPagesUseTimeAndIdAndRejectChangedFilters() throws Exception {
    String a = shop("owner"), b = shop("owner"), sa = service(a, 60), sb = service(b, 60);
    var one = slot(a, sa, "2026-10-16T12:00:00+09:00");
    var two = slot(b, sb, "2026-10-16T12:00:00+09:00");
    var three = slot(a, sa, "2026-10-16T13:00:00+09:00");
    var found = new ArrayList<String>();
    String cursor = null;
    do {
      var page = catalog.available(conditions(null, null), cursor, 1);
      found.addAll(
          page.items().stream()
              .map(com.bintime.store.contract.SearchStoreCatalog.Slot::id)
              .toList());
      cursor = page.nextCursor();
    } while (cursor != null);
    var tied = new ArrayList<>(List.of(one.path("id").asText(), two.path("id").asText()));
    Collections.sort(tied);
    assertThat(found).containsExactly(tied.get(0), tied.get(1), three.path("id").asText());
    String first = catalog.available(conditions(null, null), null, 1).nextCursor();
    assertThatThrownBy(() -> catalog.available(conditions(a, sa), first, 1))
        .hasMessage("INVALID_CURSOR");
    assertThatThrownBy(() -> catalog.available(conditions(a, sa), "broken", 1))
        .hasMessage("INVALID_CURSOR");
    assertThatThrownBy(() -> catalog.available(conditions(a, sa), null, 101))
        .hasMessage("INVALID_LIMIT");
    var c = conditions(a, sa);
    var tooCheap =
        new com.bintime.search.dto.WaitlistConditions(
            a, "NAIL", sa, c.windowStart(), c.windowEnd(), null, null, 50000L, List.of());
    assertThat(catalog.available(tooCheap, null, 20).items()).isEmpty();
    var tooShort =
        new com.bintime.search.dto.WaitlistConditions(
            a,
            "NAIL",
            sa,
            c.windowStart(),
            OffsetDateTime.parse("2026-10-16T12:30:00+09:00"),
            null,
            null,
            null,
            List.of());
    assertThat(catalog.available(tooShort, null, 20).items()).isEmpty();
  }

  @Test
  void distanceFilteringScansBeyondTheFirstRawPageAndBatchesOracleIds() throws Exception {
    String far = shop("owner"), near = shop("owner"), fs = service(far, 60), ns = service(near, 60);
    db.update("update store_shops set latitude=0,longitude=0 where store_id=?", far);
    String original = slot(far, fs, "2026-10-16T12:00:00+09:00").path("id").asText();
    for (int i = 0; i < 105; i++) {
      db.update(
          "insert into"
              + " store_slots(slot_id,store_id,service_id,start_at,end_at,available_from,available_until,original_price,payable_price,status_code,source_type,version_no,created_at)"
              + " select"
              + " ?,store_id,service_id,start_at,end_at,available_from,available_until,original_price,payable_price,status_code,source_type,version_no,created_at"
              + " from store_slots where slot_id=?",
          "raw-" + i,
          original);
    }
    String valid = slot(near, ns, "2026-10-16T13:00:00+09:00").path("id").asText();
    assertThat(catalog.available(conditions(null, null), null, 1).items())
        .extracting(com.bintime.store.contract.SearchStoreCatalog.Slot::id)
        .containsExactly(valid);
    var ids = new ArrayList<String>();
    for (int i = 0; i < 1100; i++) ids.add("missing-" + i);
    ids.add(valid);
    assertThat(catalog.revalidate(ids)).hasSize(1);
  }

  @Test
  void exportIncludesAllServicesAndUsesIndependentTransactionalSearchVersion() throws Exception {
    String a = shop("owner"), b = shop("owner");
    long initial = document(a).version();
    String svc = service(a, 60);
    assertThat(document(a).version()).isGreaterThan(initial);
    var slot = slot(a, svc, "2026-10-16T12:00:00+09:00");
    var before = document(a);
    ok(
        "PATCH",
        ownerPath(a) + "/services/" + svc,
        "owner",
        null,
        Map.of("name", "새 시술", "basePrice", 60000, "expectedVersion", 1),
        200);
    var after = document(a);
    assertThat(after.version()).isGreaterThan(before.version());
    assertThat(after.description()).contains("새 시술");
    assertThat(after.slots().getFirst().payablePrice()).isEqualTo(50001);
    assertThat(ok("GET", BASE + "/stores/" + a, null, null, null, 200).path("version").asLong())
        .isEqualTo(1);
    ok(
        "PATCH",
        ownerPath(a) + "/services/" + svc,
        "owner",
        null,
        Map.of("active", false, "expectedVersion", 2),
        200);
    assertThat(document(a).description()).contains("새 시술");
    assertThat(document(a).slots()).isEmpty();
    assertThat(catalog.revalidate(List.of(slot.path("id").asText()))).isEmpty();
    var page = catalog.export(null, 1);
    assertThat(page.items()).hasSize(1);
    assertThat(catalog.export(page.nextCursor(), 1).items().getFirst().id())
        .isNotEqualTo(page.items().getFirst().id());
    long unchanged = document(a).version();
    assertThat(
            call(
                    "PATCH",
                    ownerPath(a) + "/services/" + svc,
                    "owner",
                    null,
                    Map.of("name", "실패", "expectedVersion", 1))
                .status)
        .isEqualTo(409);
    assertThat(document(a).version()).isEqualTo(unchanged);
    assertThat(document(b).slots()).isEmpty();
  }

  private JsonNode cancel(JsonNode reservation) throws Exception {
    return ok(
        "POST",
        BASE + "/reservations/" + reservation.path("id").asText() + "/cancel",
        "alice",
        UUID.randomUUID().toString(),
        Map.of(
            "reasonCode",
            "CUSTOMER_REQUEST",
            "expectedVersion",
            reservation.path("version").asLong()),
        200);
  }

  private com.bintime.store.service.StoreEventDelivery delivery(
      com.bintime.search.service.MatchService matches) {
    return new com.bintime.store.service.StoreEventDelivery(
        storeRepository,
        (com.bintime.store.service.StoreSearchCatalogService) catalog,
        matches,
        searchIndex,
        transactions,
        clock);
  }

  @Test
  void committedChangesReachSearchQueuesOnceAcrossConcurrentConsumers() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    slot(shop, svc, "2026-10-16T12:00:00+09:00");
    var executor = Executors.newFixedThreadPool(2);
    try {
      var a = executor.submit(() -> delivery(matchService).deliver());
      var b = executor.submit(() -> delivery(matchService).deliver());
      a.get(20, TimeUnit.SECONDS);
      b.get(20, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(
            db.queryForObject(
                "select count(*) from store_events where status_code='PROCESSED'", Integer.class))
        .isEqualTo(3);
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='INDEX'", Integer.class))
        .isEqualTo(3);
    delivery(matchService).deliver();
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void failedDeliveryRollsBackSearchQueuesAndRetriesAfterBackoff() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    slot(shop, svc, "2026-10-16T12:00:00+09:00");
    db.update("delete from store_events where event_type<>'CREATED'");
    var failing = org.mockito.Mockito.mock(com.bintime.search.service.MatchService.class);
    org.mockito.Mockito.doAnswer(
            call -> {
              matchService.submit(call.getArgument(0));
              throw new IllegalStateException("after enqueue");
            })
        .when(failing)
        .submit(org.mockito.ArgumentMatchers.any());
    delivery(failing).deliver();
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
    assertThat(db.queryForObject("select status_code from store_events", String.class))
        .isEqualTo("FAILED");
    delivery(matchService).deliver();
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
    when(clock.instant()).thenReturn(NOW.plusSeconds(3));
    delivery(matchService).deliver();
    assertThat(db.queryForObject("select status_code from store_events", String.class))
        .isEqualTo("PROCESSED");
    assertThat(db.queryForObject("select attempts from store_events", Integer.class)).isEqualTo(2);
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void restartedConsumerWaitsForLeaseThenRecoversIt() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    slot(shop, svc, "2026-10-16T12:00:00+09:00");
    db.update("delete from store_events where event_type<>'CREATED'");
    db.update(
        "update store_events set lease_token='dead-worker',lease_until=?",
        OffsetDateTime.ofInstant(NOW.plusSeconds(120), ZoneOffset.UTC));
    delivery(matchService).deliver();
    assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
    when(clock.instant()).thenReturn(NOW.plusSeconds(120));
    delivery(matchService).deliver();
    assertThat(db.queryForObject("select status_code from store_events", String.class))
        .isEqualTo("PROCESSED");
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void delayedPublicationDoesNotMatchWithdrawnOrEarlierPublication() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode vacancy =
        cancel(reserve(slot(shop, svc, "2026-10-16T12:00:00+09:00"), "alice")).path("vacancy");
    JsonNode first = publish(shop, vacancy, svc, "2026-10-16T12:00:00+09:00", "delivery-pub-1");
    JsonNode withdrawn =
        ok(
            "POST",
            ownerPath(shop) + "/vacancies/" + vacancy.path("id").asText() + "/withdraw",
            "owner",
            "delivery-withdraw",
            Map.of("expectedVersion", first.at("/vacancy/version").asLong()),
            200);
    delivery(matchService).deliver();
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isZero();
    publish(shop, withdrawn.path("vacancy"), svc, "2026-10-16T12:00:00+09:00", "delivery-pub-2");
    // 오래된 이벤트도 다시 도착시키되 publication v1은 새 매칭을 만들지 않아야 한다.
    db.update("update store_events set status_code='QUEUED' where event_type='PUBLISHED'");
    delivery(matchService).deliver();
    assertThat(
            db.queryForObject("select count(*) from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select publication_version from search_work where kind='SLOT'", Long.class))
        .isEqualTo(2L);
  }

  private JsonNode publish(String shop, JsonNode vacancy, String service, String time, String key)
      throws Exception {
    return ok(
        "POST",
        ownerPath(shop) + "/vacancies/" + vacancy.path("id").asText() + "/publish",
        "owner",
        key,
        Map.of(
            "serviceId",
            service,
            "startAt",
            time,
            "discount",
            Map.of("type", "PERCENT", "value", 30),
            "expectedVersion",
            vacancy.path("version").asLong()),
        200);
  }

  @Test
  void twentyApisCompleteTheRegistrationReservationVacancyLifecycle() throws Exception {
    assertThat(ok("GET", BASE + "/stores/categories", null, null, null, 200).path("items").size())
        .isEqualTo(4);
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode slot = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    assertThat(
            ok("GET", BASE + "/stores/" + shop, null, null, null, 200).at("/rating/count").asInt())
        .isZero();
    assertThat(
            ok("GET", BASE + "/stores/" + shop + "/services", null, null, null, 200)
                .path("items")
                .size())
        .isEqualTo(1);
    assertThat(
            ok(
                    "GET",
                    BASE + "/stores/" + shop + "/slots?from=2026-10-16&to=2026-10-16",
                    null,
                    null,
                    null,
                    200)
                .path("items")
                .size())
        .isEqualTo(1);
    assertThat(
            ok(
                    "GET",
                    BASE + "/stores/" + shop + "/slots/" + slot.path("id").asText(),
                    null,
                    null,
                    null,
                    200)
                .has("resourceId"))
        .isFalse();
    JsonNode r = reserve(slot, "alice");
    String rid = r.path("id").asText();
    assertThat(r.path("customer").isNull()).isTrue();
    assertThat(
            ok("GET", BASE + "/reservations/me?scope=UPCOMING", "alice", null, null, 200)
                .at("/counts/upcoming")
                .asInt())
        .isEqualTo(1);
    ok("GET", BASE + "/reservations/" + rid, "alice", null, null, 200);
    assertThat(
            ok("GET", ownerPath(shop) + "/reservations/" + rid, "owner", null, null, 200)
                .at("/customer/id")
                .asText())
        .isEqualTo("alice");
    assertThat(
            ok("GET", ownerPath(shop) + "/timetable?date=2026-10-16", "owner", null, null, 200)
                .at("/summary/total")
                .asInt())
        .isEqualTo(1);
    ok(
        "PATCH",
        ownerPath(shop),
        "owner",
        null,
        Map.of("description", "소개", "expectedVersion", 1),
        200);
    ok(
        "PATCH",
        ownerPath(shop) + "/services/" + svc,
        "owner",
        null,
        Map.of("basePrice", 60001, "expectedVersion", 1),
        200);
    assertThat(
            ok("GET", BASE + "/reservations/" + rid, "alice", null, null, 200)
                .at("/price/original")
                .asLong())
        .isEqualTo(50001);
    JsonNode canceled = cancel(r), vac = canceled.path("vacancy");
    assertThat(vac.path("status").asText()).isEqualTo("DRAFT");
    assertThat(
            ok("GET", ownerPath(shop) + "/vacancies", "owner", null, null, 200)
                .at("/counts/awaitingPublication")
                .asInt())
        .isEqualTo(1);
    JsonNode pub = publish(shop, vac, svc, "2026-10-16T12:00:00+09:00", "pub1");
    assertThat(pub.path("matchingStatus").asText()).isEqualTo("QUEUED");
    assertThat(pub.at("/slot/price/payable").asLong()).isEqualTo(42000);
    String withdraw = ownerPath(shop) + "/vacancies/" + vac.path("id").asText() + "/withdraw";
    JsonNode withdrawn =
        ok(
            "POST",
            withdraw,
            "owner",
            "with1",
            Map.of("expectedVersion", pub.at("/vacancy/version").asLong()),
            200);
    JsonNode repub =
        publish(shop, withdrawn.path("vacancy"), svc, "2026-10-16T12:00:00+09:00", "pub2");
    assertThat(repub.at("/slot/publicationVersion").asLong()).isEqualTo(2);
    JsonNode newR = reserve(repub.path("slot"), "bob");
    assertThat(
            call(
                    "POST",
                    withdraw,
                    "owner",
                    "with2",
                    Map.of("expectedVersion", repub.at("/vacancy/version").asLong()))
                .code())
        .isEqualTo("VACANCY_ALREADY_FILLED");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T04:00:00Z"));
    ok(
        "POST",
        ownerPath(shop) + "/reservations/" + newR.path("id").asText() + "/status",
        "owner",
        "complete",
        Map.of("status", "COMPLETED", "expectedVersion", 1),
        200);
    assertThat(db.queryForObject("select count(*) from store_reservations", Integer.class))
        .isEqualTo(2);
    assertThat(
            db.queryForObject(
                "select count(*) from store_events where status_code='QUEUED'", Integer.class))
        .isGreaterThanOrEqualTo(6);
  }

  @Test
  void permissionsInputNullPatchesAndVersionsAreEnforced() throws Exception {
    assertThat(call("POST", BASE + "/owner/stores", null, "x", Map.of()).status).isEqualTo(401);
    assertThat(call("POST", BASE + "/owner/stores", "alice", "x", Map.of()).status).isEqualTo(400);
    String shop = shop("owner"), svc = service(shop, 60);
    assertThat(
            call(
                    "PATCH",
                    ownerPath(shop),
                    "otherOwner",
                    null,
                    Map.of("description", "x", "expectedVersion", 1))
                .status)
        .isEqualTo(403);
    assertThat(
            call(
                    "PATCH",
                    ownerPath(shop),
                    "owner",
                    null,
                    "{\"description\":null,\"expectedVersion\":1}")
                .status)
        .isEqualTo(400);
    assertThat(
            call(
                    "PATCH",
                    ownerPath(shop) + "/services/" + svc,
                    "owner",
                    null,
                    "{\"active\":null,\"expectedVersion\":1}")
                .status)
        .isEqualTo(400);
    assertThat(
            call(
                    "PATCH",
                    ownerPath(shop),
                    "owner",
                    null,
                    Map.of("description", "x", "expectedVersion", 8))
                .code())
        .isEqualTo("VERSION_CONFLICT");
    JsonNode updated =
        ok(
            "PATCH",
            ownerPath(shop),
            "owner",
            null,
            Map.of("description", "", "expectedVersion", 1),
            200);
    assertThat(updated.path("openingHours").size()).isEqualTo(7);
    assertThat(
            call(
                    "GET",
                    BASE + "/stores/" + shop + "/slots?from=2026-10-16&to=2026-11-16",
                    null,
                    null,
                    null)
                .status)
        .isEqualTo(400);
    assertThat(
            call("GET", BASE + "/stores/" + shop + "/services?limit=101", null, null, null).status)
        .isEqualTo(400);
    assertThat(
            call(
                    "POST",
                    ownerPath(shop) + "/slots",
                    "owner",
                    "invalid",
                    Map.of("serviceId", svc, "startAt", "2026-10-16T08:00:00+09:00"))
                .status)
        .isEqualTo(409);
  }

  @Test
  void cursorPagesHaveStableOrderingFilterValidationAndFullCounts() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    for (int h = 12; h <= 14; h++)
      reserve(slot(shop, svc, "2026-10-16T" + h + ":00:00+09:00"), "alice");
    JsonNode first =
        ok("GET", BASE + "/reservations/me?scope=UPCOMING&limit=1", "alice", null, null, 200);
    assertThat(first.at("/counts/upcoming").asInt()).isEqualTo(3);
    String cursor = first.at("/page/nextCursor").asText();
    JsonNode second =
        ok(
            "GET",
            BASE + "/reservations/me?scope=UPCOMING&limit=1&cursor=" + cursor,
            "alice",
            null,
            null,
            200);
    assertThat(second.at("/counts/upcoming").asInt()).isEqualTo(3);
    assertThat(second.at("/items/0/startAt").asText())
        .isLessThan(first.at("/items/0/startAt").asText());
    assertThat(
            call("GET", BASE + "/reservations/me?scope=PAST&cursor=" + cursor, "alice", null, null)
                .code())
        .isEqualTo("INVALID_CURSOR");
    assertThat(
            call(
                    "GET",
                    BASE + "/reservations/me?scope=UPCOMING&cursor=garbage",
                    "alice",
                    null,
                    null)
                .code())
        .isEqualTo("INVALID_CURSOR");
  }

  @Test
  void idempotencyReplaysOriginalResultRejectsChangesAndAllowsRetryAfterFailure() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode s = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    var body = new HashMap<>(booking(s));
    body.put("expectedPrice", 1);
    assertThat(call("POST", BASE + "/reservations", "alice", "same", body).code())
        .isEqualTo("PRICE_CHANGED");
    JsonNode first = ok("POST", BASE + "/reservations", "alice", "same", booking(s), 201);
    cancel(first);
    assertThat(ok("POST", BASE + "/reservations", "alice", "same", booking(s), 201))
        .isEqualTo(first);
    assertThat(call("POST", BASE + "/reservations", "alice", "same", body).code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(db.queryForObject("select count(*) from store_reservations", Integer.class))
        .isEqualTo(1);
  }

  private List<Result> race(Callable<Result> one, Callable<Result> two) throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var a =
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return one.call();
              });
      var b =
          pool.submit(
              () -> {
                ready.countDown();
                start.await();
                return two.call();
              });
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
    }
  }

  @Test
  void sameSlotCanOnlyBeBookedOnceUnderContention() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode s = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    long searchVersion = document(shop).version();
    var results =
        race(
            () -> call("POST", BASE + "/reservations", "alice", "a", booking(s)),
            () -> call("POST", BASE + "/reservations", "bob", "b", booking(s)));
    assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
    assertThat(document(shop).version()).isEqualTo(searchVersion + 1);
    assertThat(
            db.queryForObject(
                "select count(*) from store_reservations where status_code='CONFIRMED'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void oneCustomerCannotBookOverlappingAppointmentsAtDifferentStores() throws Exception {
    String shop = shop("owner"),
        svc = service(shop, 60),
        shop2 = shop("owner"),
        svc2 = service(shop2, 60);
    JsonNode a = slot(shop, svc, "2026-10-16T12:00:00+09:00"),
        b = slot(shop2, svc2, "2026-10-16T12:00:00+09:00");
    var results =
        race(
            () -> call("POST", BASE + "/reservations", "alice", "a", booking(a)),
            () -> call("POST", BASE + "/reservations", "alice", "b", booking(b)));
    assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
  }

  @Test
  void simultaneousSameKeyRequestsReturnOneSuccessfulReservation() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode s = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    long searchVersion = document(shop).version();
    var results =
        race(
            () -> call("POST", BASE + "/reservations", "alice", "same", booking(s)),
            () -> call("POST", BASE + "/reservations", "alice", "same", booking(s)));
    assertThat(results.stream().map(Result::status)).containsExactly(201, 201);
    assertThat(results.get(0).data()).isEqualTo(results.get(1).data());
    assertThat(document(shop).version()).isEqualTo(searchVersion + 1);
    assertThat(db.queryForObject("select count(*) from store_reservations", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void reservationAndWithdrawalRaceLeavesConsistentSupply() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode v =
        cancel(reserve(slot(shop, svc, "2026-10-16T12:00:00+09:00"), "alice")).path("vacancy");
    JsonNode p = publish(shop, v, svc, "2026-10-16T12:00:00+09:00", "publish");
    var results =
        race(
            () -> call("POST", BASE + "/reservations", "bob", "book", booking(p.path("slot"))),
            () ->
                call(
                    "POST",
                    ownerPath(shop) + "/vacancies/" + v.path("id").asText() + "/withdraw",
                    "owner",
                    "withdraw",
                    Map.of("expectedVersion", p.at("/vacancy/version").asLong())));
    assertThat(results.stream().map(Result::status)).containsAnyOf(200, 201).contains(409);
    String status =
        db.queryForObject(
            "select status_code from store_slots where slot_id=?",
            String.class,
            v.path("id").asText());
    assertThat(status).isIn("BOOKED", "CLOSED");
    assertThat(
            db.queryForObject(
                "select count(*) from store_reservations where status_code='CONFIRMED'",
                Integer.class))
        .isEqualTo(status.equals("BOOKED") ? 1 : 0);
  }

  @Test
  void timeBoundariesNoShowPartialVacanciesExpiryAndPastCancellation() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode s = slot(shop, svc, "2026-10-16T12:00:00+09:00"), r = reserve(s, "alice");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:09:59Z"));
    String status = ownerPath(shop) + "/reservations/" + r.path("id").asText() + "/status";
    assertThat(
            call(
                    "POST",
                    status,
                    "owner",
                    "early",
                    Map.of("status", "NO_SHOW", "expectedVersion", 1))
                .code())
        .isEqualTo("STATUS_TOO_EARLY");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T03:10:00Z"));
    JsonNode noShow =
        ok(
            "POST",
            status,
            "owner",
            "early",
            Map.of("status", "NO_SHOW", "expectedVersion", 1),
            200);
    assertThat(noShow.at("/vacancy/availableFrom").asText()).contains("12:10:00");
    assertThat(
            call(
                    "POST",
                    ownerPath(shop)
                        + "/vacancies/"
                        + noShow.at("/vacancy/id").asText()
                        + "/publish",
                    "owner",
                    "tooLong",
                    Map.of(
                        "serviceId",
                        svc,
                        "startAt",
                        "2026-10-16T12:15:00+09:00",
                        "discount",
                        Map.of("type", "PERCENT", "value", 30),
                        "expectedVersion",
                        1))
                .code())
        .isEqualTo("VACANCY_TIME_MISMATCH");
    String shortSvc = service(shop, 30);
    publish(shop, noShow.path("vacancy"), shortSvc, "2026-10-16T12:15:00+09:00", "short");
    JsonNode later = slot(shop, svc, "2026-10-16T14:00:00+09:00");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T05:00:00Z"));
    assertThat(call("POST", BASE + "/reservations", "bob", "expired", booking(later)).code())
        .isEqualTo("SLOT_EXPIRED");
    assertThat(
            ok(
                    "GET",
                    BASE + "/stores/" + shop + "/slots/" + later.path("id").asText(),
                    null,
                    null,
                    null,
                    200)
                .path("status")
                .asText())
        .isEqualTo("EXPIRED");
    when(clock.instant()).thenReturn(NOW);
    JsonNode past = reserve(slot(shop, svc, "2026-10-16T16:00:00+09:00"), "alice");
    when(clock.instant()).thenReturn(Instant.parse("2026-10-16T08:00:00Z"));
    assertThat(cancel(past).path("vacancy").isNull()).isTrue();
  }

  @Test
  void waitlistOwnershipStateConditionsAndCommitRollbackAreChecked() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode s = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    db.update(
        "insert into"
            + " search_waitlists(waitlist_id,user_id,store_id,service_id,category_code,window_start,window_end,status_code,version_no,max_price)"
            + " values('wait','bob',?,?,'NAIL',?,?,'ACTIVE',1,60000)",
        shop,
        svc,
        OffsetDateTime.parse("2026-10-16T11:00:00+09:00"),
        OffsetDateTime.parse("2026-10-16T14:00:00+09:00"));
    var body = new HashMap<>(booking(s));
    body.put("waitlistId", "wait");
    assertThat(call("POST", BASE + "/reservations", "alice", "wait", body).code())
        .isEqualTo("WAITLIST_FORBIDDEN");
    db.update("update search_waitlists set user_id='alice',max_price=1 where waitlist_id='wait'");
    assertThat(call("POST", BASE + "/reservations", "alice", "wait", body).code())
        .isEqualTo("WAITLIST_CONDITION_MISMATCH");
    assertThat(
            db.queryForObject(
                "select status_code from store_slots where slot_id=?",
                String.class,
                s.path("id").asText()))
        .isEqualTo("AVAILABLE");
    db.update(
        "update search_waitlists set max_price=60000,status_code='FULFILLED' where"
            + " waitlist_id='wait'");
    assertThat(call("POST", BASE + "/reservations", "alice", "wait", body).code())
        .isEqualTo("WAITLIST_INACTIVE");
    db.update("update search_waitlists set status_code='ACTIVE' where waitlist_id='wait'");
    ok("POST", BASE + "/reservations", "alice", "wait", body, 201);
    assertThat(
            db.queryForObject(
                "select status_code from search_waitlists where waitlist_id='wait'", String.class))
        .isEqualTo("FULFILLED");
  }

  private void seedIntent(String id, String user, String shop, String svc) throws Exception {
    var c =
        new com.bintime.search.dto.WaitlistConditions(
            shop,
            "NAIL",
            svc,
            OffsetDateTime.parse("2026-10-16T11:00:00+09:00"),
            OffsetDateTime.parse("2026-10-16T14:00:00+09:00"),
            null,
            null,
            60000L,
            List.of());
    db.update(
        "insert into"
            + " search_intent(id,user_id,source,conditions_json,status,resource_version,created_at,consent_at,window_end,consent_version)"
            + " values(?,?,'MANUAL',?,'ACTIVE',1,?,?,?,1)",
        id,
        user,
        json.writeValueAsString(c),
        NOW.toEpochMilli(),
        NOW.toEpochMilli(),
        c.windowEnd().toInstant().toEpochMilli());
  }

  @Test
  void searchIntentFulfillmentSharesTheReservationTransactionAndRollsBackOnStorageFailure()
      throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode slot = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    seedIntent("new-wait", "alice", shop, svc);
    var body = new HashMap<>(booking(slot));
    body.put("waitlistId", "new-wait");
    long searchVersion = document(shop).version();
    // 실제 DB 실패를 대기 완료 이후에 발생시켜 JDBC search와 JPA store의 원자성을 검증한다.
    db.execute(
        "alter table store_events add constraint bt_test_reject_booked"
            + " check(event_type<>'BOOKED')");
    try {
      assertThat(call("POST", BASE + "/reservations", "alice", "retry", body).status())
          .isEqualTo(500);
      assertThat(
              db.queryForObject(
                  "select status from search_intent where id='new-wait'", String.class))
          .isEqualTo("ACTIVE");
      assertThat(db.queryForObject("select count(*) from search_work", Integer.class)).isZero();
      assertThat(db.queryForObject("select count(*) from store_reservations", Integer.class))
          .isZero();
      assertThat(document(shop).version()).isEqualTo(searchVersion);
      assertThat(
              db.queryForObject(
                  "select status_code from store_slots where slot_id=?",
                  String.class,
                  slot.path("id").asText()))
          .isEqualTo("AVAILABLE");
    } finally {
      db.execute("alter table store_events drop constraint bt_test_reject_booked");
    }
    JsonNode booked = ok("POST", BASE + "/reservations", "alice", "retry", body, 201);
    assertThat(
            db.queryForObject("select status from search_intent where id='new-wait'", String.class))
        .isEqualTo("FULFILLED");
    assertThat(
            db.queryForObject(
                "select reservation_id from search_intent where id='new-wait'", String.class))
        .isEqualTo(booked.path("id").asText());
    assertThat(
            db.queryForObject(
                "select count(*) from search_work where kind='VECTOR'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void absentWaitlistAndNewIntentOwnershipAreRejected() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode slot = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    var body = new HashMap<>(booking(slot));
    body.put("waitlistId", "missing");
    assertThat(call("POST", BASE + "/reservations", "alice", "retry", body).code())
        .isEqualTo("WAITLIST_NOT_FOUND");
    seedIntent("new-wait", "bob", shop, svc);
    body.put("waitlistId", "new-wait");
    assertThat(call("POST", BASE + "/reservations", "alice", "retry", body).code())
        .isEqualTo("WAITLIST_FORBIDDEN");
  }

  @Test
  void adjacentReservationsAreAllowedAndOverlappingSupplyIsRejected() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode first = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    assertThat(
            call(
                    "POST",
                    ownerPath(shop) + "/slots",
                    "owner",
                    "overlap",
                    Map.of("serviceId", svc, "startAt", "2026-10-16T12:30:00+09:00"))
                .code())
        .isEqualTo("SLOT_TIME_CONFLICT");
    reserve(first, "alice");
    reserve(slot(shop, svc, "2026-10-16T13:00:00+09:00"), "alice");
    assertThat(db.queryForObject("select count(*) from store_reservations", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void serviceRegistrationKeysExpireAfter24HoursAndCannotMoveBetweenStores() throws Exception {
    String shop = shop("owner"), other = shop("owner");
    var body = Map.of("name", "시술", "durationMinutes", 60, "basePrice", 10000, "active", true);
    JsonNode first = ok("POST", ownerPath(shop) + "/services", "owner", "stable", body, 201);
    assertThat(ok("POST", ownerPath(shop) + "/services", "owner", "stable", body, 201))
        .isEqualTo(first);
    assertThat(call("POST", ownerPath(other) + "/services", "owner", "stable", body).code())
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    when(clock.instant()).thenReturn(NOW.plusSeconds(86400));
    assertThat(ok("POST", ownerPath(shop) + "/services", "owner", "stable", body, 201).path("id"))
        .isNotEqualTo(first.path("id"));
  }

  @Test
  void timetableReturnsAnExplicitErrorInsteadOfTruncatingPastItsCap() throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    JsonNode slot = slot(shop, svc, "2026-10-16T12:00:00+09:00");
    String sql =
        "insert into"
            + " store_reservations(reservation_id,user_id,store_id,slot_id,service_id,service_name,duration_minutes,customer_name,start_at,end_at,original_price,payable_price,status_code,version_no,created_at)"
            + " values(?,'alice',?,?,?,'과거"
            + " 시술',60,'alice',?,?,50001,50001,'CANCELED',1,CURRENT_TIMESTAMP)";
    var args = new ArrayList<Object[]>();
    for (int i = 0; i < 499; i++)
      args.add(
          new Object[] {
            "cap_" + i,
            shop,
            slot.path("id").asText(),
            svc,
            OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
            OffsetDateTime.parse("2026-10-16T13:00:00+09:00")
          });
    db.batchUpdate(sql, args);
    ok("GET", ownerPath(shop) + "/timetable?date=2026-10-16", "owner", null, null, 200);
    db.update(
        sql,
        "cap_500",
        shop,
        slot.path("id").asText(),
        svc,
        OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
        OffsetDateTime.parse("2026-10-16T13:00:00+09:00"));
    assertThat(
            call("GET", ownerPath(shop) + "/timetable?date=2026-10-16", "owner", null, null).code())
        .isEqualTo("TIMETABLE_LIMIT_EXCEEDED");
  }

  @Test
  void registeredDiscountIsPreservedEvenWhenRoundingChangesTheEffectivePercentage()
      throws Exception {
    String shop = shop("owner"), svc = service(shop, 60);
    ok(
        "PATCH",
        ownerPath(shop) + "/services/" + svc,
        "owner",
        null,
        Map.of("basePrice", 1, "expectedVersion", 1),
        200);
    JsonNode v =
        cancel(reserve(slot(shop, svc, "2026-10-16T12:00:00+09:00"), "alice")).path("vacancy");
    String path = ownerPath(shop) + "/vacancies/" + v.path("id").asText();
    JsonNode pub =
        ok(
            "POST",
            path + "/publish",
            "owner",
            "percent",
            Map.of(
                "serviceId",
                svc,
                "startAt",
                "2026-10-16T12:00:00+09:00",
                "discount",
                Map.of("type", "PERCENT", "value", 5),
                "expectedVersion",
                1),
            200);
    assertThat(pub.at("/slot/price/payable").asLong()).isZero();
    assertThat(pub.at("/slot/price/discountPercent").asInt()).isEqualTo(5);
    JsonNode withdrawn =
        ok(
            "POST",
            path + "/withdraw",
            "owner",
            "withdraw",
            Map.of("expectedVersion", pub.at("/vacancy/version").asLong()),
            200);
    JsonNode fixed =
        ok(
            "POST",
            path + "/publish",
            "owner",
            "fixed",
            Map.of(
                "serviceId",
                svc,
                "startAt",
                "2026-10-16T12:00:00+09:00",
                "discount",
                Map.of("type", "FIXED_PRICE", "value", 1),
                "expectedVersion",
                withdrawn.at("/vacancy/version").asLong()),
            200);
    assertThat(fixed.at("/slot/price/payable").asLong()).isEqualTo(1);
    // 정액 할인가에는 등록하지 않은 할인율을 역산해 붙이지 않는다.
    assertThat(fixed.at("/slot/price/discountPercent").isNull()).isTrue();
  }

  @Test
  void malformedPatchAndMissingDaysAreInputErrors() throws Exception {
    String shop = shop("owner");
    assertThat(call("PATCH", ownerPath(shop), "owner", null, "{").status()).isEqualTo(400);
    assertThat(
            call(
                    "PATCH",
                    ownerPath(shop),
                    "owner",
                    null,
                    Map.of(
                        "openingHours",
                        List.of(Map.of("dayOfWeek", "MON", "closed", true)),
                        "expectedVersion",
                        1))
                .code())
        .isEqualTo("INVALID_OPENING_HOURS");
  }
}
