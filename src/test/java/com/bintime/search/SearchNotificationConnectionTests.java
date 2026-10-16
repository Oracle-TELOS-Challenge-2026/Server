package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.common.auth.Actor;
import com.bintime.notification.config.NotificationSchemaVerifier;
import com.bintime.notification.contract.SearchNotificationGateway;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.service.*;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.search.dto.*;
import com.bintime.search.port.*;
import com.bintime.search.service.*;
import com.bintime.store.dto.*;
import com.bintime.store.repository.StoreRepository;
import com.bintime.store.service.*;
import com.bintime.user.contract.SearchUserAccess;
import com.bintime.user.security.BintimePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "notification.delivery.minimum-interval-seconds=0"
    })
@ActiveProfiles("test")
class SearchNotificationConnectionTests {
  @Autowired ApplicationContext context;
  @Autowired DataSource source;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired StoreApplicationService stores;
  @Autowired StoreRepository storeDb;
  @Autowired StoreSearchCatalogService catalog;
  @Autowired SearchIndexService indexes;
  @Autowired MatchService matching;
  @Autowired SearchWorker search;
  @Autowired WaitlistService waitlists;
  @Autowired SearchUserAccess users;
  @Autowired NotificationSubscriptions subscriptions;
  @Autowired NotificationDeliveryWorker delivery;
  @Autowired TransactionTemplate tx;
  @Autowired java.time.Clock clock;
  @MockitoBean StoreIndexPort index;
  @MockitoBean IntentAiPort ai;
  @MockitoBean FcmClient fcm;

  @DynamicPropertySource
  static void oracle(DynamicPropertyRegistry p) {
    String user = System.getenv("NOTIFICATION_ORACLE_USER");
    if (user == null) return;
    if (!user.matches("BT_NOTIFY_[A-F0-9]{12}"))
      throw new IllegalArgumentException("Disposable schema required");
    p.add("spring.datasource.url", () -> System.getenv("NOTIFICATION_ORACLE_URL"));
    p.add("spring.datasource.username", () -> user);
    p.add("spring.datasource.password", () -> System.getenv("NOTIFICATION_ORACLE_PASSWORD"));
    p.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
    p.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    p.add("spring.sql.init.mode", () -> "never");
  }

  @Test
  void eventsMatchEveryCustomerDeduplicateAndSuppressStaleDeliveries() throws Exception {
    if (System.getenv("NOTIFICATION_ORACLE_USER") == null) {
      String ddl =
          new ClassPathResource("notification-delivery-schema.sql")
              .getContentAsString(StandardCharsets.UTF_8);
      new ResourceDatabasePopulator(
              new ByteArrayResource(
                  ddl.substring(ddl.indexOf(';') + 1).getBytes(StandardCharsets.UTF_8)))
          .execute(source);
      new ResourceDatabasePopulator(new ClassPathResource("notification-subscriptions-schema.sql"))
          .execute(source);
    }
    new NotificationSchemaVerifier(db).run(null);
    assertThat(context.getBeansOfType(SearchNotificationGateway.class)).hasSize(1);
    assertThat(context.getBean(SearchNotificationGateway.class))
        .isInstanceOf(NotificationService.class);
    assertThat(context.getBeansOfType(com.bintime.store.contract.SearchStoreCatalog.class))
        .hasSize(1);
    when(ai.embed(anyString()))
        .thenAnswer(
            a -> {
              float[] v = new float[1536];
              v[0] = 1;
              return v;
            });
    when(fcm.enabled()).thenReturn(true);
    when(fcm.send(anyString(), any(), any(BooleanSupplier.class)))
        .thenAnswer(
            a -> {
              assertThat(a.<BooleanSupplier>getArgument(2).getAsBoolean()).isTrue();
              return new FcmClient.Result(true, null, false, 0);
            });
    db.update(
        "insert into user_accounts(user_id,display_name,role_code)"
            + " values('notify-owner','사장님','OWNER')");
    var owner = new Actor("notify-owner", "OWNER", "사장님");
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    new UpdateStoreRequest.OpeningHourItem(
                        day, LocalTime.of(9, 0), LocalTime.of(22, 0), false))
            .toList();
    var shop =
        stores.createStore(
            owner,
            "notify-shop",
            new CreateStoreRequest(
                "알림 검증 매장",
                "NAIL",
                "서울",
                new StoreDetailResponse.Location(37.5, 127.0),
                "젤 네일",
                null,
                hours));
    var service =
        stores.createService(
            owner,
            shop.id(),
            "notify-service",
            new CreateStoreServiceRequest("젤 네일", 60, 50000L, true));
    var start =
        LocalDate.now(ZoneId.of("Asia/Seoul"))
            .plusDays(1)
            .atTime(12, 0)
            .atZone(ZoneId.of("Asia/Seoul"))
            .toOffsetDateTime();
    var intents = new ArrayList<WaitlistResponse>();
    for (int n = 0; n < 7; n++) {
      String user = "notify-user-" + n;
      db.update(
          "insert into user_accounts(user_id,display_name,role_code) values(?,?,'CUSTOMER')",
          user,
          user);
      var principal = new BintimePrincipal(user, "CUSTOMER");
      var session = new MockHttpSession();
      var security = SecurityContextHolder.createEmptyContext();
      security.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
      session.setAttribute(
          HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, security);
      subscriptions.register(principal, session, "notify-fid-" + n);
      assertThat(users.notificationsEnabled(user)).isTrue();
      for (int k = 0; k < 2; k++)
        intents.add(
            waitlists.create(
                user,
                "wait-" + n + "-" + k,
                new CreateWaitlistRequest(
                    "MANUAL",
                    new WaitlistConditions(
                        shop.id(),
                        "NAIL",
                        service.id(),
                        start.minusHours(k + 1),
                        start.plusHours(6),
                        null,
                        null,
                        50000L,
                        List.of()),
                    true)));
    }
    // No subscription: must not receive an alert even if a waitlist matches.
    db.update(
        "insert into user_accounts(user_id,display_name,role_code) values('notify-off','구독"
            + " 없음','CUSTOMER')");
    waitlists.create(
        "notify-off",
        "wait-off",
        new CreateWaitlistRequest("MANUAL", intents.getFirst().conditions(), true));
    assertThat(users.notificationsEnabled("notify-off")).isFalse();
    var slot =
        stores.createSlot(
            owner, shop.id(), "notify-slot", new CreateSlotRequest(service.id(), start));
    var events = new StoreEventDelivery(storeDb, catalog, matching, indexes, tx, clock);
    events.deliver();
    for (int n = 0; n < 8; n++) search.poll();
    assertThat(db.queryForObject("select count(*) from notification_alerts", Integer.class))
        .isEqualTo(7);
    assertThat(db.queryForObject("select count(*) from notification_dedup", Integer.class))
        .isEqualTo(7);
    for (String request :
        db.queryForList("select request_json from notification_alerts", String.class))
      assertThat(
              json.readValue(
                      request, com.bintime.notification.dto.DispatchNotificationRequest.class)
                  .matches())
          .hasSize(2);
    events.deliver();
    for (int n = 0; n < 3; n++) search.poll();
    delivery.run();
    delivery.run();
    verify(fcm, times(7)).send(anyString(), any(), any(BooleanSupplier.class));
    assertThat(
            db.queryForObject(
                "select count(*) from notification_deliveries where status='ACCEPTED'",
                Integer.class))
        .isEqualTo(7);
    // A second slot creates new candidates, then waitlist cancellation and booking invalidate them.
    var second =
        stores.createSlot(
            owner,
            shop.id(),
            "notify-second",
            new CreateSlotRequest(service.id(), start.plusHours(2)));
    events.deliver();
    for (int n = 0; n < 5; n++) search.poll();
    assertThat(db.queryForObject("select count(*) from notification_alerts", Integer.class))
        .isEqualTo(14);
    for (WaitlistResponse intent : intents.subList(0, 2))
      waitlists.cancel(
          "notify-user-0",
          intent.id(),
          "cancel-" + intent.id(),
          new CancelWaitlistRequest(intent.version()));
    stores.reserve(
        new Actor("notify-user-1", "CUSTOMER", "고객"),
        "notify-book",
        new CreateReservationRequest(
            second.id(), second.price().payable(), second.version(), intents.get(2).id()));
    delivery.run();
    verify(fcm, times(7)).send(anyString(), any(), any(BooleanSupplier.class));
    assertThat(
            db.queryForObject(
                "select count(*) from notification_deliveries where status='SKIPPED'",
                Integer.class))
        .isEqualTo(7);
    assertThat(waitlists.get("notify-user-1", intents.get(2).id()).status()).isEqualTo("FULFILLED");
  }
}
