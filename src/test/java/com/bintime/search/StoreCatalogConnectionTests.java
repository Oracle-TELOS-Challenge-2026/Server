package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.common.auth.Actor;
import com.bintime.search.port.*;
import com.bintime.search.service.*;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.StoreDocument;
import com.bintime.store.dto.*;
import com.bintime.store.service.StoreApplicationService;
import com.bintime.store.service.StoreSearchCatalogService;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(StoreCatalogConnectionTests.IndexConfig.class)
class StoreCatalogConnectionTests {
  static final String ALIAS = "catalog-connection-" + UUID.randomUUID();
  static final Instant NOW = Instant.parse("2026-10-16T01:00:00Z");
  static final Actor OWNER = new Actor("catalog-owner", "OWNER", "사장님");
  @LocalServerPort int port;
  @Autowired ApplicationContext context;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired SearchStoreCatalog catalog;
  @Autowired StoreApplicationService stores;
  @Autowired SearchIndexService indexes;
  @Autowired SearchWorker worker;
  @Autowired WaitlistService waitlists;

  @Autowired
  @Qualifier("connectionIndex")
  StoreIndexPort index;

  @MockitoBean(name = "storeClock")
  Clock clock;

  @MockitoBean IntentAiPort ai;
  String indexName;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry props) {
    props.add("search.elasticsearch.alias", () -> ALIAS);
    // An explicitly provisioned disposable Oracle schema only, never the app schema.
    String user = System.getenv("CATALOG_ORACLE_USER");
    if (user != null) {
      if (!user.matches("BT_CATALOG_[A-F0-9]{12}"))
        throw new IllegalArgumentException("Isolated schema required");
      props.add("spring.datasource.url", () -> System.getenv("CATALOG_ORACLE_URL"));
      props.add("spring.datasource.username", () -> user);
      props.add("spring.datasource.password", () -> System.getenv("CATALOG_ORACLE_PASSWORD"));
      props.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
      props.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
      props.add("spring.sql.init.mode", () -> "never");
    }
  }

  @TestConfiguration
  static class IndexConfig {
    @Bean
    @Primary
    StoreIndexPort connectionIndex(JsonMapper json) {
      String url = System.getenv("SEARCH_ELASTICSEARCH_URL");
      return url == null
          ? mock(StoreIndexPort.class)
          : new com.bintime.search.adapter.ElasticsearchStoreAdapter(json, url, ALIAS, "", "", "");
    }
  }

  @BeforeEach
  void setup() {
    when(clock.instant()).thenReturn(NOW);
    when(clock.millis()).thenReturn(NOW.toEpochMilli());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    float[] vector = new float[1536];
    vector[0] = 1;
    doReturn(vector).when(ai).embed(anyString());
    if (mockingDetails(index).isMock()) {
      var document = new AtomicReference<StoreDocument>();
      doAnswer(
              a -> {
                document.set(a.getArgument(1));
                return null;
              })
          .when(index)
          .upsert(anyString(), any(), nullable(float[].class));
      when(index.open()).thenReturn("pit");
      when(index.search(any(), anyString(), anyList(), nullable(float[].class), anyInt()))
          .thenAnswer(
              a ->
                  new StoreIndexPort.Page(
                      "pit",
                      ((List<?>) a.getArgument(2)).isEmpty() && document.get() != null
                          ? List.of(
                              new StoreIndexPort.Hit(document.get(), List.of(document.get().id())))
                          : List.of()));
    }
    for (String id : List.of("catalog-owner", "catalog-customer"))
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,?)",
          id,
          id,
          id.equals(OWNER.id()) ? "OWNER" : "CUSTOMER");
  }

  @Test
  void realStoreCatalogFeedsRebuildWaitlistsAndSearchAndRejectsBookedSlot() throws Exception {
    assertThat(context.getBeansOfType(SearchStoreCatalog.class)).hasSize(1);
    assertThat(catalog).isInstanceOf(StoreSearchCatalogService.class);
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    new UpdateStoreRequest.OpeningHourItem(
                        day, LocalTime.of(9, 0), LocalTime.of(22, 0), false))
            .toList();
    var shop =
        stores.createStore(
            OWNER,
            "catalog-shop",
            new CreateStoreRequest(
                "실제 네일숍",
                "NAIL",
                "서울",
                new StoreDetailResponse.Location(37.5, 127.0),
                "조용한 네일 아트 전문",
                null,
                hours));
    var service =
        stores.createService(
            OWNER,
            shop.id(),
            "catalog-service",
            new CreateStoreServiceRequest("원컬러 젤", 60, 50000L, true));
    var start = OffsetDateTime.parse("2026-10-17T15:00:00+09:00");
    var slot =
        stores.createSlot(
            OWNER, shop.id(), "catalog-slot", new CreateSlotRequest(service.id(), start));
    var conditions =
        new com.bintime.search.dto.WaitlistConditions(
            shop.id(),
            "NAIL",
            service.id(),
            start,
            start.plusHours(2),
            null,
            null,
            50000L,
            List.of());
    var intent =
        waitlists.create(
            "catalog-customer",
            "catalog-wait",
            new com.bintime.search.dto.CreateWaitlistRequest("MANUAL", conditions, true));
    assertThat(waitlists.get("catalog-customer", intent.id()).store().id()).isEqualTo(shop.id());
    assertThat(catalog.available(conditions, null, 100).items())
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly(slot.id());
    indexName = indexes.rebuild();
    worker.poll();
    worker.poll();
    assertThat(
            db.queryForObject(
                "select count(*) from search_store_vector where store_id=?",
                Integer.class,
                shop.id()))
        .isEqualTo(1);
    for (String sort : List.of("DISTANCE", "PRICE", "RECOMMENDED")) {
      var response = search(sort);
      assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
      assertThat(json.readTree(response.body()).at("/success/data/items/0/bestSlot/id").asText())
          .isEqualTo(slot.id());
    }
    stores.reserve(
        new Actor("catalog-customer", "CUSTOMER", "고객"),
        "catalog-reserve",
        new CreateReservationRequest(slot.id(), 50000L, slot.version(), intent.id()));
    assertThat(catalog.revalidate(List.of(slot.id()))).isEmpty();
    // Deliberately leave ES stale: search must still suppress the now-booked slot.
    var response = search("PRICE");
    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    assertThat(json.readTree(response.body()).at("/success/data/items").size()).isZero();
    assertThat(waitlists.get("catalog-customer", intent.id()).status()).isEqualTo("FULFILLED");
  }

  HttpResponse<String> search(String sort) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:"
                            + port
                            + "/api/v1/search/stores?latitude=37.5&longitude=127&category=NAIL&sort="
                            + sort
                            + "&from=2026-10-17T06:00:00Z&to=2026-10-17T08:00:00Z"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
  }

  @AfterEach
  void cleanupIndex() throws Exception {
    if (indexName != null && System.getenv("SEARCH_ELASTICSEARCH_URL") != null)
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(
                      URI.create(System.getenv("SEARCH_ELASTICSEARCH_URL") + "/" + indexName))
                  .DELETE()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
  }
}
