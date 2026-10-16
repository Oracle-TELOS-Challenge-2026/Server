package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.bintime.search.dto.CreateWaitlistRequest;
import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.port.IntentAiPort;
import com.bintime.search.service.*;
import com.bintime.store.repository.StoreRepository;
import com.bintime.store.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 실제 ES와 store outbox/search worker를 함께 검증한다. 운영 alias는 사용하지 않는다. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop", "bintime.auth.local-enabled=true",
      "bintime.store.event-delivery-enabled=false", "search.scheduling-enabled=false"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "SEARCH_ELASTICSEARCH_URL", matches = ".+")
class StoreEventFlowTests {
  static final String ALIAS = "event-flow-" + UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
  static final String START = "2026-10-17T15:00:00+09:00";
  static final AtomicBoolean ES_UNAVAILABLE = new AtomicBoolean();
  static HttpServer proxy;
  static String es;
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired StoreRepository storeRepository;
  @Autowired StoreSearchCatalogService catalog;
  @Autowired StoreApplicationService stores;
  @Autowired SearchIndexService indexes;
  @Autowired MatchService matches;
  @Autowired SearchWorker worker;
  @Autowired WaitlistService waitlists;
  @Autowired TransactionTemplate transactions;

  @MockitoBean(name = "storeClock")
  Clock clock;

  @MockitoBean IntentAiPort ai;
  final HttpClient client = HttpClient.newHttpClient();
  Instant now = NOW;
  String indexName;
  String shop;
  String service;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties) throws Exception {
    es = System.getenv("SEARCH_ELASTICSEARCH_URL");
    URI endpoint = URI.create(es);
    if (!Set.of("localhost", "127.0.0.1").contains(endpoint.getHost()))
      throw new IllegalArgumentException("Local disposable Elasticsearch required");
    proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var upstream = HttpClient.newHttpClient();
    proxy.createContext(
        "/",
        exchange -> {
          try {
            byte[] body;
            int status;
            if (ES_UNAVAILABLE.get()
                && exchange.getRequestMethod().equals("PUT")
                && exchange.getRequestURI().getPath().contains("/_doc/")) {
              status = 503;
              body = "{\"error\":\"test-only ES outage\"}".getBytes(StandardCharsets.UTF_8);
            } else {
              byte[] input = exchange.getRequestBody().readAllBytes();
              var request =
                  HttpRequest.newBuilder(URI.create(es + exchange.getRequestURI()))
                      .timeout(Duration.ofSeconds(15))
                      .header("Content-Type", "application/json")
                      .method(
                          exchange.getRequestMethod(),
                          input.length == 0
                              ? HttpRequest.BodyPublishers.noBody()
                              : HttpRequest.BodyPublishers.ofByteArray(input));
              var response =
                  upstream.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
              status = response.statusCode();
              body = response.body();
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
              output.write(body);
            }
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            exchange.close();
          }
        });
    proxy.start();
    properties.add(
        "search.elasticsearch.url", () -> "http://127.0.0.1:" + proxy.getAddress().getPort());
    properties.add("search.elasticsearch.alias", () -> ALIAS);
    String user = System.getenv("FLOW_ORACLE_USER");
    if (user != null) {
      String url = System.getenv("FLOW_ORACLE_URL");
      if (!user.matches("BT_STORE_[A-F0-9]{12}")
          || url == null
          || !url.matches("jdbc:oracle:thin:@//localhost:[0-9]+/FREEPDB1"))
        throw new IllegalArgumentException("Isolated local Oracle schema required");
      properties.add("spring.datasource.url", () -> url);
      properties.add("spring.datasource.username", () -> user);
      properties.add("spring.datasource.password", () -> System.getenv("FLOW_ORACLE_PASSWORD"));
      properties.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
      properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
      properties.add("spring.sql.init.mode", () -> "never");
    }
  }

  @BeforeEach
  void setup() {
    advance(NOW);
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    float[] vector = new float[1536];
    vector[0] = 1;
    when(ai.embed(anyString())).thenReturn(vector);
    for (String id : List.of("owner", "alice", "bob"))
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,?)",
          id,
          id,
          id.equals("owner") ? "OWNER" : "CUSTOMER");
  }

  void advance(Instant instant) {
    now = instant;
    when(clock.instant()).thenReturn(instant);
    when(clock.millis()).thenReturn(instant.toEpochMilli());
  }

  JsonNode api(String method, String path, String actor, Object body, int status) throws Exception {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", UUID.randomUUID().toString());
    if (actor != null) request.header("X-Local-User-Id", actor);
    var response =
        client.send(
            request
                .method(
                    method,
                    body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(status);
    return json.readTree(response.body()).at("/success/data");
  }

  JsonNode reserve(JsonNode slot, String user, String wait) throws Exception {
    var body = new LinkedHashMap<String, Object>();
    body.put("slotId", slot.path("id").asText());
    body.put("expectedPrice", slot.at("/price/payable").asLong());
    body.put("expectedSlotVersion", slot.path("version").asLong());
    if (wait != null) body.put("waitlistId", wait);
    return api("POST", "/reservations", user, body, 201);
  }

  JsonNode cancel(JsonNode reservation, String user) throws Exception {
    return api(
            "POST",
            "/reservations/" + reservation.path("id").asText() + "/cancel",
            user,
            Map.of(
                "reasonCode",
                "CUSTOMER_REQUEST",
                "expectedVersion",
                reservation.path("version").asLong()),
            200)
        .path("vacancy");
  }

  JsonNode publish(JsonNode vacancy) throws Exception {
    return api(
        "POST",
        "/owner/stores/" + shop + "/vacancies/" + vacancy.path("id").asText() + "/publish",
        "owner",
        Map.of(
            "serviceId",
            service,
            "startAt",
            START,
            "discount",
            Map.of("type", "PERCENT", "value", 30),
            "expectedVersion",
            vacancy.path("version").asLong()),
        200);
  }

  String waitlist(String key) {
    var start = OffsetDateTime.parse(START);
    return waitlists
        .create(
            "bob",
            key,
            new CreateWaitlistRequest(
                "MANUAL",
                new WaitlistConditions(
                    shop,
                    "NAIL",
                    service,
                    start,
                    start.plusHours(2),
                    null,
                    null,
                    40000L,
                    List.of()),
                true))
        .id();
  }

  StoreEventDelivery delivery() {
    return new StoreEventDelivery(storeRepository, catalog, matches, indexes, transactions, clock);
  }

  void drain() {
    delivery().deliver();
    // 알림 전송은 이번 범위 밖이다. 나머지는 실제 worker의 claim/실행/ack 경로를 사용한다.
    for (int round = 0; round < 20; round++) {
      var ready =
          db.queryForList(
              "select id from search_work where kind<>'NOTIFY' and status in ('QUEUED','RUNNING')"
                  + " and available_at<=?",
              String.class,
              now.toEpochMilli());
      if (ready.isEmpty()) return;
      ready.forEach(worker::run);
    }
    fail("Search queues did not settle");
  }

  JsonNode es(String method, String path) throws Exception {
    var response =
        client.send(
            HttpRequest.newBuilder(URI.create(es + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).as(response.body()).isBetween(200, 299);
    return json.readTree(response.body());
  }

  void visible(String slot) throws Exception {
    var source = es("GET", "/" + ALIAS + "/_doc/" + shop).path("_source");
    var ids = new ArrayList<String>();
    source.path("slots").forEach(s -> ids.add(s.path("id").asText()));
    assertThat(ids).containsExactly(slot);
    var search =
        api(
            "GET",
            "/search/stores?latitude=37.51335413975466&longitude=126.92649449142719&radiusMeters=3000&vacancyOnly=true&from=2026-10-17T06:00:00Z&to=2026-10-17T08:00:00Z",
            null,
            null,
            200);
    assertThat(search.at("/items/0/bestSlot/id").asText()).isEqualTo(slot);
  }

  void invisible() throws Exception {
    assertThat(es("GET", "/" + ALIAS + "/_doc/" + shop).at("/_source/slots").size()).isZero();
    var result =
        api(
            "GET",
            "/search/stores?latitude=37.51335413975466&longitude=126.92649449142719&radiusMeters=3000&vacancyOnly=true",
            null,
            null,
            200);
    assertThat(result.path("items").size()).isZero();
  }

  @Test
  void publicationBookingWithdrawalExpiryAndHttpFailureReachRealElasticsearch() throws Exception {
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    Map.of(
                        "dayOfWeek", day, "opensAt", "09:00", "closesAt", "22:00", "closed", false))
            .toList();
    shop =
        api(
                "POST",
                "/owner/stores",
                "owner",
                Map.of(
                    "name",
                    "통합 QA 네일",
                    "category",
                    "NAIL",
                    "address",
                    "서울",
                    "location",
                    Map.of("latitude", 37.51335413975466, "longitude", 126.92649449142719),
                    "openingHours",
                    hours),
                201)
            .path("id")
            .asText();
    service =
        api(
                "POST",
                "/owner/stores/" + shop + "/services",
                "owner",
                Map.of("name", "원컬러", "durationMinutes", 60, "basePrice", 50000, "active", true),
                201)
            .path("id")
            .asText();
    var regular =
        api(
            "POST",
            "/owner/stores/" + shop + "/slots",
            "owner",
            Map.of("serviceId", service, "startAt", START),
            201);
    var draft = cancel(reserve(regular, "alice", null), "alice");
    String wait = waitlist("wait-one");
    indexName = indexes.rebuild();
    drain();
    invisible();
    var first = publish(draft);
    var oldDocument = catalog.document(shop);
    String vacancyId = draft.path("id").asText();
    drain();
    visible(vacancyId);
    assertThat(waitlists.get("bob", wait).offers()).hasSize(1);
    assertThat(
            db.queryForObject(
                "select count(*) from search_offer where intent_id=?", Integer.class, wait))
        .isEqualTo(1);
    long jobs = db.queryForObject("select count(*) from search_work where kind='SLOT'", Long.class);
    db.update("update store_events set status_code='QUEUED' where event_type='PUBLISHED'");
    drain();
    assertThat(db.queryForObject("select count(*) from search_work where kind='SLOT'", Long.class))
        .isEqualTo(jobs);
    assertThat(
            db.queryForObject(
                "select count(*) from search_offer where intent_id=?", Integer.class, wait))
        .isEqualTo(1);
    var booked = reserve(first.path("slot"), "bob", wait);
    drain();
    invisible();
    assertThat(waitlists.get("bob", wait).status()).isEqualTo("FULFILLED");
    var nextDraft = cancel(booked, "bob");
    drain();
    invisible();
    String waitTwo = waitlist("wait-two");
    var second = publish(nextDraft);
    drain();
    visible(nextDraft.path("id").asText());
    var withdrawn =
        api(
                "POST",
                "/owner/stores/"
                    + shop
                    + "/vacancies/"
                    + nextDraft.path("id").asText()
                    + "/withdraw",
                "owner",
                Map.of("expectedVersion", second.at("/vacancy/version").asLong()),
                200)
            .path("vacancy");
    drain();
    invisible();
    assertThat(waitlists.get("bob", waitTwo).offers()).isEmpty();
    var third = publish(withdrawn);
    ES_UNAVAILABLE.set(true);
    drain();
    assertThat(
            db.queryForObject(
                "select count(*) from store_events where status_code<>'PROCESSED'", Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from search_work where kind='INDEX' and status='QUEUED' and"
                    + " attempts>0",
                Integer.class))
        .isGreaterThan(0);
    invisible();
    ES_UNAVAILABLE.set(false);
    advance(NOW.plusSeconds(3));
    drain();
    visible(nextDraft.path("id").asText());
    assertThat(waitlists.get("bob", waitTwo).offers()).hasSize(1);
    assertThat(waitlists.get("bob", waitTwo).offers().getFirst().publicationVersion())
        .isEqualTo(third.at("/slot/publicationVersion").asLong());
    assertThat(
            db.queryForObject(
                "select count(*) from search_offer where intent_id=?", Integer.class, waitTwo))
        .isEqualTo(1);
    long latestVersion = catalog.document(shop).version();
    indexes.changed(oldDocument);
    drain();
    assertThat(es("GET", "/" + ALIAS + "/_doc/" + shop).at("/_source/document/version").asLong())
        .isEqualTo(latestVersion);
    visible(nextDraft.path("id").asText());
    advance(OffsetDateTime.parse(START).toInstant());
    stores.expireStore(shop);
    drain();
    invisible();
    assertThat(
            db.queryForObject(
                "select count(*) from search_work where kind='INDEX' and status<>'SUCCEEDED'",
                Integer.class))
        .isZero();
    System.out.println(
        "EVENT_FLOW verified: publication, matching, duplicate replay, booking, withdrawal,"
            + " republish, HTTP 503 retry, expiry; alias="
            + ALIAS);
  }

  @AfterEach
  void cleanup() throws Exception {
    ES_UNAVAILABLE.set(false);
    if (indexName != null) es("DELETE", "/" + indexName);
  }

  @AfterAll
  static void stopProxy() {
    if (proxy != null) proxy.stop(0);
  }
}
