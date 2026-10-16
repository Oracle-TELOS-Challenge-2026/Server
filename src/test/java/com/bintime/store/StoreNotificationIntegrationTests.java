package com.bintime.store;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.bintime.common.auth.Actor;
import com.bintime.notification.dto.NotificationResponse;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.service.NotificationDeliveryWorker;
import com.bintime.notification.service.NotificationService;
import com.bintime.search.dto.*;
import com.bintime.search.port.IntentAiPort;
import com.bintime.search.port.StoreIndexPort;
import com.bintime.search.service.*;
import com.bintime.store.dto.*;
import com.bintime.store.repository.StoreRepository;
import com.bintime.store.service.*;
import com.bintime.user.security.BintimePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/** 실제 store outbox → search 큐 → notification 저장/발송을 연결한다. 외부 공급자만 대체한다. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "bintime.auth.local-enabled=false",
      "notification.scheduling-enabled=false",
      "notification.delivery.minimum-interval-seconds=0"
    })
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class StoreNotificationIntegrationTests {
  static final Actor OWNER = new Actor("owner", "OWNER", "사장님");
  static final OffsetDateTime START = OffsetDateTime.parse("2026-10-17T15:00:00+09:00");
  final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-16T01:00:00Z"));
  @Autowired WebApplicationContext context;
  @Autowired DataSource source;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired StoreApplicationService stores;
  @Autowired StoreSearchCatalogService catalog;
  @Autowired StoreRepository storeRepository;
  @Autowired WaitlistService waitlists;
  @Autowired MatchService matches;
  @Autowired SearchIndexService indexes;
  @Autowired SearchWorker search;
  @Autowired NotificationService notifications;
  @Autowired NotificationDeliveryWorker delivery;
  @Autowired TransactionTemplate tx;
  @MockitoBean FcmClient fcm;
  @MockitoBean IntentAiPort ai;
  @MockitoBean StoreIndexPort index;

  @MockitoBean(name = "storeClock")
  Clock clock;

  MockMvc mvc;
  StoreEventDelivery outbox;
  StoreDetailResponse shop;
  StoreServiceResponse service;
  VacancyResponse vacancy;
  final Map<String, MockHttpSession> sessions = new HashMap<>();
  final Map<String, com.bintime.search.dto.WaitlistResponse> intents = new HashMap<>();

  @BeforeEach
  void setup() throws Exception {
    when(clock.instant()).thenAnswer(a -> now.get());
    when(clock.millis()).thenAnswer(a -> now.get().toEpochMilli());
    when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    // 테스트마다 별도 H2 DB를 사용해 다른 테스트나 로컬 Oracle 데이터를 변경하지 않는다.
    String schema =
        new ClassPathResource("notification-delivery-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    new ResourceDatabasePopulator(
            new ByteArrayResource(
                schema.substring(schema.indexOf(';') + 1).getBytes(StandardCharsets.UTF_8)))
        .execute(source);
    new ResourceDatabasePopulator(new ClassPathResource("notification-subscriptions-schema.sql"))
        .execute(source);
    for (String id : List.of("owner", "original", "alice", "bob", "no-push", "too-cheap"))
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,?)",
          id,
          id,
          id.equals("owner") ? "OWNER" : "CUSTOMER");
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    outbox = new StoreEventDelivery(storeRepository, catalog, matches, indexes, tx, clock);
    float[] vector = new float[1536];
    vector[0] = 1;
    when(ai.embed(anyString())).thenReturn(vector);
    when(fcm.enabled()).thenReturn(true);
    when(fcm.send(anyString(), any(), any()))
        .thenAnswer(
            a ->
                a.<BooleanSupplier>getArgument(2).getAsBoolean()
                    ? new FcmClient.Result(true, null, false, 0)
                    : new FcmClient.Result(false, "STALE_CONDITIONS", false, 0));
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    new UpdateStoreRequest.OpeningHourItem(
                        day, LocalTime.of(9, 0), LocalTime.of(22, 0), false))
            .toList();
    shop =
        stores.createStore(
            OWNER,
            "shop",
            new CreateStoreRequest(
                "연동 네일숍",
                "NAIL",
                "서울",
                new StoreDetailResponse.Location(37.51335413975466, 126.92649449142719),
                "젤 네일",
                null,
                hours));
    service =
        stores.createService(
            OWNER, shop.id(), "service", new CreateStoreServiceRequest("젤 네일", 60, 50000L, true));
    var regular =
        stores.createSlot(OWNER, shop.id(), "slot", new CreateSlotRequest(service.id(), START));
    var reservation =
        stores.reserve(
            actor("original"),
            "original-booking",
            new CreateReservationRequest(regular.id(), 50000L, regular.version(), null));
    vacancy =
        stores
            .cancel(
                actor("original"),
                reservation.id(),
                "original-cancel",
                new CancelReservationRequest("CUSTOMER_REQUEST", reservation.version(), null))
            .vacancy();
    pump();
    for (String id : List.of("alice", "bob", "no-push", "too-cheap")) {
      if (!id.equals("no-push")) subscribe(id);
      intents.put(
          id,
          waitlists.create(
              id,
              "wait-" + id,
              new CreateWaitlistRequest(
                  "MANUAL",
                  new WaitlistConditions(
                      shop.id(),
                      "NAIL",
                      service.id(),
                      START,
                      START.plusHours(2),
                      null,
                      null,
                      id.equals("too-cheap") ? 10000L : 50000L,
                      List.of()),
                  true)));
    }
    pump();
    assertThat(count("notification_alerts")).isZero();
  }

  @Test
  void publicationMatchesEligibleCustomersAndReplaysDoNotDuplicateAlerts() throws Exception {
    var published = publish(vacancy.version(), "publish");
    assertThat(published.matchingStatus()).isEqualTo("QUEUED");
    assertThat(count("notification_alerts")).isZero();
    pump();
    matches.submit(
        new CreateMatchJobRequest(
            "replayed-publication", published.slot().id(), published.slot().publicationVersion()));
    pump();
    delivery.run();
    delivery.run();
    assertThat(count("notification_alerts")).isEqualTo(2);
    assertThat(
            db.queryForList(
                "select user_id from notification_alerts order by user_id", String.class))
        .containsExactly("alice", "bob");
    assertThat(count("notification_events")).isEqualTo(2);
    assertThat(db.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("ACCEPTED")
        .hasSize(2);
    verify(fcm, times(2)).send(anyString(), any(), any());
    for (String id : List.of("alice", "bob")) {
      mvc.perform(get("/api/v1/notifications").session(sessions.get(id)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.success.data.items[0].target.slotId").value(published.slot().id()))
          .andExpect(jsonPath("$.success.data.items[0].target.publicationVersion").value(1))
          .andExpect(jsonPath("$.success.data.items[0].availability").value("AVAILABLE"));
    }
  }

  @Test
  void bookingFulfillsTheWaitlistAndSuppressesAlreadyQueuedPushes() throws Exception {
    var published = publish(vacancy.version(), "publish");
    pump();
    var intent = intents.get("alice");
    var session = sessions.get("alice");
    mvc.perform(
            post("/api/v1/reservations")
                .session(session)
                .header("X-CSRF-Token", csrf(session))
                .header("Idempotency-Key", "book-vacancy")
                .contentType("application/json")
                .content(
                    json.writeValueAsString(
                        new CreateReservationRequest(
                            published.slot().id(),
                            40000L,
                            published.slot().version(),
                            intent.id()))))
        .andExpect(status().isCreated());
    pump();
    assertThat(waitlists.get("alice", intent.id()).status()).isEqualTo("FULFILLED");
    assertUnavailableAndSkipped();
  }

  @ParameterizedTest
  @ValueSource(strings = {"WITHDRAWN", "EXPIRED"})
  void closingASlotSuppressesQueuedPushesAndUpdatesNotificationAvailability(String close) {
    var published = publish(vacancy.version(), "publish");
    pump();
    if (close.equals("WITHDRAWN")) {
      stores.withdraw(
          OWNER,
          shop.id(),
          vacancy.id(),
          "withdraw",
          new WithdrawVacancyRequest(published.slot().version()));
    } else {
      now.set(START.toInstant());
      stores.expireStore(shop.id());
    }
    pump();
    assertUnavailableAndSkipped();
  }

  @Test
  void delayedPublicationAfterWithdrawalCreatesNoAlerts() {
    var published = publish(vacancy.version(), "publish");
    stores.withdraw(
        OWNER,
        shop.id(),
        vacancy.id(),
        "withdraw",
        new WithdrawVacancyRequest(published.slot().version()));
    pump();
    delivery.run();
    assertThat(count("notification_alerts")).isZero();
    verify(fcm, never()).send(anyString(), any(), any());
  }

  @Test
  void republishingInvalidatesOldPushesAndPreservesTheUserSlotDeduplicationContract() {
    var first = publish(vacancy.version(), "publish");
    pump();
    var closed =
        stores.withdraw(
            OWNER,
            shop.id(),
            vacancy.id(),
            "withdraw",
            new WithdrawVacancyRequest(first.slot().version()));
    var second = publish(closed.slot().version(), "republish");
    pump();
    delivery.run();
    assertThat(second.slot().publicationVersion()).isEqualTo(2);
    assertThat(count("notification_alerts")).isEqualTo(2);
    assertUnavailableAndSkipped();
    assertThat(db.queryForList("select distinct publication_version from search_offer", Long.class))
        .containsExactly(2L);
  }

  @Test
  void providerRetryKeepsOneAlertAndUsesTheSameNotificationIdentity() {
    publish(vacancy.version(), "publish");
    pump();
    doReturn(new FcmClient.Result(false, "UNAVAILABLE", true, 60))
        .when(fcm)
        .send(anyString(), any(), any());
    delivery.run();
    assertThat(db.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("QUEUED");
    now.updateAndGet(i -> i.plusSeconds(61));
    doAnswer(
            a -> {
              assertThat(a.<BooleanSupplier>getArgument(2).getAsBoolean()).isTrue();
              return new FcmClient.Result(true, null, false, 0);
            })
        .when(fcm)
        .send(anyString(), any(), any());
    pump();
    delivery.run();
    var sent = org.mockito.ArgumentCaptor.forClass(NotificationResponse.class);
    verify(fcm, times(4)).send(anyString(), sent.capture(), any());
    assertThat(sent.getAllValues().stream().map(NotificationResponse::id).distinct()).hasSize(2);
    assertThat(count("notification_alerts")).isEqualTo(2);
    assertThat(db.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("ACCEPTED");
  }

  private PublishVacancyResponse publish(long version, String key) {
    return stores.publish(
        OWNER,
        shop.id(),
        vacancy.id(),
        key,
        new PublishVacancyRequest(
            service.id(), START, new PublishVacancyRequest.Discount("PERCENT", 20L), version));
  }

  private void assertUnavailableAndSkipped() {
    delivery.run();
    assertThat(count("notification_alerts")).isEqualTo(2);
    for (String id : List.of("alice", "bob"))
      assertThat(notifications.list(principal(id), false, null, 20).items())
          .singleElement()
          .satisfies(n -> assertThat(n.availability()).isEqualTo("UNAVAILABLE"));
    assertThat(db.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("SKIPPED")
        .hasSize(2);
    verify(fcm, never()).send(anyString(), any(), any());
  }

  private void pump() {
    // 스케줄러를 기다리지 않고 실제 영속 큐를 비운다. 실패·지연 작업을 성공처럼 넘기지 않는다.
    outbox.deliver();
    for (int attempt = 0; attempt < 20; attempt++) {
      search.poll();
      if (db.queryForObject(
              "select count(*) from search_work where status<>'SUCCEEDED'", Integer.class)
          == 0) break;
    }
    assertThat(db.queryForList("select status from search_work", String.class))
        .containsOnly("SUCCEEDED");
    assertThat(db.queryForList("select status_code from store_events", String.class))
        .containsOnly("PROCESSED");
  }

  private void subscribe(String user) throws Exception {
    var principal = principal(user);
    var session = new MockHttpSession();
    session.setMaxInactiveInterval(-1);
    session.setAttribute(
        HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
        new SecurityContextImpl(
            new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao")));
    sessions.put(user, session);
    mvc.perform(
            post("/api/v1/notifications/push-subscriptions")
                .session(session)
                .header("X-CSRF-Token", csrf(session))
                .contentType("application/json")
                .content(json.writeValueAsString(Map.of("installationId", "fid-" + user))))
        .andExpect(status().isCreated());
  }

  private String csrf(MockHttpSession session) throws Exception {
    var response =
        mvc.perform(get("/api/v1/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    return json.readTree(response.getContentAsString()).at("/success/data/csrfToken").asText();
  }

  private int count(String table) {
    return db.queryForObject("select count(*) from " + table, Integer.class);
  }

  private static Actor actor(String id) {
    return new Actor(id, "CUSTOMER", id);
  }

  private static BintimePrincipal principal(String id) {
    return new BintimePrincipal(id, "CUSTOMER");
  }
}
