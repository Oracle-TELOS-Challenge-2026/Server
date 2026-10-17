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
    db.update("update user_accounts set preferences_json=?", "{\"text\":\"젤 네일을 선호해요\"}");
    // 가격 제한 대기의 회귀 후보는 프로필 선호를 비워, 별도 무대기 추천 대상과 구분한다.
    db.update("update user_accounts set preferences_json='{}' where user_id='too-cheap'");
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

  private float[] semanticVector(boolean relevant) {
    float[] value = new float[1536];
    value[relevant ? 0 : 1] = 1;
    return value;
  }

  @Test
  void changedStoreAndServiceDescriptionCannotUseTheOldCachedVector() {
    publish(vacancy.version(), "store-semantic-recheck");
    pump();
    stores.updateStore(
        OWNER,
        shop.id(),
        new UpdateStoreRequest("헤어만 제공하는 설명으로 변경", stores.getStore(shop.id()).version(), null));
    when(ai.embed(anyString()))
        .thenAnswer(a -> semanticVector(!a.<String>getArgument(0).contains("헤어만")));
    delivery.run();
    verify(fcm, never()).send(anyString(), any(), any());
    pump();
    now.updateAndGet(i -> i.plusSeconds(61));
    delivery.run();
    verify(fcm, never()).send(anyString(), any(), any());
    assertThat(db.queryForList("select status from notification_deliveries", String.class))
        .containsOnly("SKIPPED");
  }

  @Test
  void sameCategoryTimeAndPriceStillRequirePreferenceSimilarityBeforeSending() {
    // 두 고객의 NAIL 대기는 동일하다. 프로필 벡터만 직교시켜 유사도 필터를 검증한다.
    db.update(
        "update user_accounts set preferences_json=? where user_id='bob'",
        "{\"text\":\"헤어만 원해요\"}");
    when(ai.embed(anyString()))
        .thenAnswer(a -> semanticVector(!a.<String>getArgument(0).contains("헤어만")));
    publish(vacancy.version(), "semantic-publish");
    pump();
    delivery.run();
    assertThat(db.queryForList("select user_id from notification_alerts", String.class))
        .containsExactly("alice");
    assertThat(
            db.queryForObject(
                "select count(*) from notification_deliveries d join notification_alerts a on"
                    + " a.notification_id=d.notification_id where a.user_id='bob'",
                Integer.class))
        .isZero();
    verify(fcm, times(1)).send(anyString(), any(), any());
  }

  @Test
  void changedPreferencesAreCheckedAgainBeforeFcmSubmission() {
    publish(vacancy.version(), "semantic-recheck");
    pump();
    assertThat(count("notification_alerts")).isEqualTo(2);
    db.update(
        "update user_accounts set preferences_json=? where user_id='bob'",
        "{\"text\":\"헤어만 원해요\"}");
    when(ai.embed(anyString()))
        .thenAnswer(a -> semanticVector(!a.<String>getArgument(0).contains("헤어만")));
    delivery.run();
    // 변경된 선호의 벡터가 준비될 때까지 bob은 보내지 않고 재시도한다.
    verify(fcm, times(1)).send(anyString(), any(), any());
    pump();
    now.updateAndGet(i -> i.plusSeconds(61));
    delivery.run();
    verify(fcm, times(1)).send(anyString(), any(), any());
    assertThat(
            db.queryForList(
                "select d.status from notification_deliveries d join notification_alerts a on"
                    + " a.notification_id=d.notification_id where a.user_id='bob'",
                String.class))
        .containsOnly("SKIPPED");
  }

  @Test
  void missingVectorWaitsThroughOpenAiFailureAndRecoversWithoutDuplicateAlerts() {
    db.update(
        "update user_accounts set preferences_json=? where user_id='alice'",
        "{\"text\":\"새로운 네일 취향\"}");
    when(ai.embed(anyString())).thenThrow(new EmbeddingFailure("AI_UNAVAILABLE", true));
    publish(vacancy.version(), "semantic-wait");
    outbox.deliver();
    for (int i = 0; i < 3; i++) {
      search.poll();
      now.updateAndGet(at -> at.plusSeconds(2));
    }
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='alice'", Integer.class))
        .isZero();
    doReturn(semanticVector(true)).when(ai).embed(anyString());
    db.update("update search_work set available_at=0 where status='QUEUED'");
    pump();
    delivery.run();
    assertThat(
            db.queryForList(
                "select user_id from notification_alerts order by user_id", String.class))
        .containsExactly("alice", "bob");
    verify(fcm, times(2)).send(anyString(), any(), any());
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

  private void personalizedCustomer(String id, String preference, boolean consent)
      throws Exception {
    db.update(
        "insert into"
            + " user_accounts(user_id,display_name,role_code,preferences_json,recommendation_opt_in,recommendation_consent_at)"
            + " values(?,?,'CUSTOMER',?,?,?)",
        id,
        id,
        "{\"text\":\"" + preference + "\"}",
        consent ? "Y" : "N",
        consent ? OffsetDateTime.now(clock) : null);
    subscribe(id);
  }

  @Test
  void personalizedVacancyIncludesDefaultNAccountsAndStillFiltersSimilarity() throws Exception {
    personalizedCustomer("similar", "젤 네일", true);
    // 서울 매장과 멀리 떨어진 저장 위치도 시연의 맞춤 알림 자격에 영향을 주지 않는다.
    db.execute("alter table user_accounts add latitude decimal(9,6)");
    db.execute("alter table user_accounts add longitude decimal(10,6)");
    db.update(
        "update user_accounts set latitude=-33.8688,longitude=151.2093 where user_id='similar'");
    personalizedCustomer("unrelated", "헤어만", false);
    personalizedCustomer("default-n", "젤 네일", false);
    when(ai.embed(anyString()))
        .thenAnswer(a -> semanticVector(!a.<String>getArgument(0).contains("헤어만")));
    var published = publish(vacancy.version(), "personalized");
    pump();
    matches.submit(
        new CreateMatchJobRequest(
            "repeat-personalized", published.slot().id(), published.slot().publicationVersion()));
    pump();
    delivery.run();
    assertThat(
            db.queryForList(
                "select user_id from notification_alerts order by user_id", String.class))
        .containsExactly("alice", "bob", "default-n", "similar");
    var notice = notifications.list(principal("similar"), false, null, 20).items().getFirst();
    var defaultN = notifications.list(principal("default-n"), false, null, 20).items().getFirst();
    assertThat(defaultN.target().waitlistIds()).isEmpty();
    assertThat(
            db.queryForObject(
                "select recommendation_opt_in from user_accounts where user_id='default-n'",
                String.class))
        .isEqualTo("N");
    assertThat(notice.target().waitlistIds()).isEmpty();
    assertThat(notice.target().slotId()).isEqualTo(published.slot().id());
    assertThat(
            db.queryForObject(
                "select count(*) from search_intent where user_id='similar'", Integer.class))
        .isZero();
    verify(fcm, times(4)).send(anyString(), any(), any());
  }

  @Test
  void changingLegacyFlagToNDoesNotSuppressPersonalizedFcm() throws Exception {
    personalizedCustomer("similar", "네일", true);
    publish(vacancy.version(), "personalized-optout");
    pump();
    db.update(
        "update user_accounts set recommendation_opt_in='N',recommendation_consent_at=null where"
            + " user_id='similar'");
    delivery.run();
    assertThat(
            db.queryForList(
                "select d.status from notification_deliveries d join notification_alerts a on"
                    + " a.notification_id=d.notification_id where a.user_id='similar'",
                String.class))
        .containsExactly("ACCEPTED");
    verify(fcm, times(3)).send(anyString(), any(), any());
  }

  @Test
  void legacyRefreshAndWaitlistAudienceDoNotDuplicateDefaultNNotifications() throws Exception {
    personalizedCustomer("late", "네일", false);
    db.update(
        "update user_accounts set"
            + " recommendation_opt_in='Y',recommendation_consent_at=CURRENT_TIMESTAMP where"
            + " user_id='alice'");
    publish(vacancy.version(), "personalized-existing");
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='alice'", Integer.class))
        .isEqualTo(1);
    db.update(
        "update user_accounts set"
            + " recommendation_opt_in='Y',recommendation_consent_at=CURRENT_TIMESTAMP where"
            + " user_id='late'");
    context.publishEvent(
        new com.bintime.user.contract.NotificationPreferencesChanged("late", true));
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='late'", Integer.class))
        .isEqualTo(1);
    delivery.run();
    verify(fcm, times(3)).send(anyString(), any(), any());
  }

  @Test
  void preferenceChangeReconsidersExistingVacancyForPersonalizedCustomer() throws Exception {
    personalizedCustomer("changed", "헤어만", false);
    when(ai.embed(anyString()))
        .thenAnswer(a -> semanticVector(!a.<String>getArgument(0).contains("헤어만")));
    publish(vacancy.version(), "personalized-preference");
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='changed'", Integer.class))
        .isZero();
    context
        .getBean(com.bintime.user.service.UserPreferenceService.class)
        .replace("changed", "{\"text\":\"네일\"}");
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='changed'", Integer.class))
        .isEqualTo(1);
  }

  @Test
  void regularSlotAndMissingActiveDeviceDoNotGeneratePersonalizedAlerts() throws Exception {
    personalizedCustomer("personal", "네일", false);
    stores.createSlot(
        OWNER,
        shop.id(),
        "personal-regular",
        new CreateSlotRequest(service.id(), START.plusHours(3)));
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='personal'", Integer.class))
        .isZero();
    // 미발행 취소 빈자리와 일반 슬롯은 추천 알림 공급이 아니다. 활성 기기도 다시 확인한다.
    db.update("update notification_subscriptions set active_yn='N' where user_id='personal'");
    publish(vacancy.version(), "personal-no-device");
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='personal'", Integer.class))
        .isZero();
  }

  @Test
  void noShowDraftDoesNotNotifyUntilOwnerPublishesItsRemainingTime() throws Exception {
    personalizedCustomer("no-show-customer", "네일", true);
    var start = START.plusHours(3);
    var regular =
        stores.createSlot(
            OWNER, shop.id(), "no-show-slot", new CreateSlotRequest(service.id(), start));
    var booked =
        stores.reserve(
            actor("original"),
            "no-show-reservation",
            new CreateReservationRequest(regular.id(), 50000L, regular.version(), null));
    now.set(start.plusMinutes(10).toInstant());
    var result =
        stores.updateStatus(
            OWNER,
            shop.id(),
            booked.id(),
            "no-show-status",
            new UpdateReservationStatusRequest("NO_SHOW", booked.version()));
    assertThat(result.vacancy().status()).isEqualTo("DRAFT");
    pump();
    delivery.run();
    assertThat(count("notification_alerts")).isZero();
    var shorter =
        stores.createService(
            OWNER,
            shop.id(),
            "short-no-show-service",
            new CreateStoreServiceRequest("원컬러 네일", 30, 30000L, true));
    var published =
        stores.publish(
            OWNER,
            shop.id(),
            result.vacancy().id(),
            "publish-no-show",
            new PublishVacancyRequest(
                shorter.id(),
                start.plusMinutes(15),
                new PublishVacancyRequest.Discount("PERCENT", 20L),
                result.vacancy().version()));
    pump();
    delivery.run();
    assertThat(db.queryForList("select user_id from notification_alerts", String.class))
        .containsExactlyInAnyOrder("alice", "bob", "no-show-customer");
    assertThat(
            notifications
                .list(principal("no-show-customer"), false, null, 20)
                .items()
                .getFirst()
                .target()
                .slotId())
        .isEqualTo(published.slot().id());
    verify(fcm, times(3)).send(anyString(), any(), any());
  }

  @Test
  void registeringDeviceDiscoversExistingVacanciesForDefaultNAccount() throws Exception {
    db.update(
        "insert into user_accounts(user_id,display_name,role_code,preferences_json)"
            + " values('late-device','후보','CUSTOMER',?)",
        "{\"text\":\"네일\"}");
    publish(vacancy.version(), "published-before-device");
    pump();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='late-device'",
                Integer.class))
        .isZero();
    subscribe("late-device");
    pump();
    delivery.run();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='late-device'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select recommendation_opt_in from user_accounts where user_id='late-device'",
                String.class))
        .isEqualTo("N");
    verify(fcm, times(3)).send(anyString(), any(), any());
  }

  @Test
  void emptySignupPreferencesDoNotBecomeGenericRecommendationVectors() throws Exception {
    String empty = "{\"categories\":[],\"keywords\":[],\"maxPrice\":null}";
    db.update(
        "insert into user_accounts(user_id,display_name,role_code,preferences_json)"
            + " values('empty-signup','신규 고객','CUSTOMER',?)",
        empty);
    subscribe("empty-signup");
    publish(vacancy.version(), "empty-signup-publish");
    pump();
    delivery.run();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_alerts where user_id='empty-signup'",
                Integer.class))
        .isZero();
    assertThat(
            db.queryForObject(
                "select count(*) from notification_push_history where user_id='empty-signup'",
                Integer.class))
        .isZero();
    verify(ai, never()).embed(empty);
    verify(fcm, times(2)).send(anyString(), any(), any());
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
      // 벡터 생성 대기 작업의 재시도 시각만 진행한다. 실제 스케줄러나 sleep은 사용하지 않는다.
      now.updateAndGet(i -> i.plusSeconds(2));
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
