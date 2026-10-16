package com.bintime.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.bintime.common.auth.Actor;
import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.notification.fcm.FcmClient;
import com.bintime.notification.service.NotificationDeliveryWorker;
import com.bintime.notification.service.NotificationService;
import com.bintime.notification.subscription.NotificationSubscriptions;
import com.bintime.search.dto.CancelWaitlistRequest;
import com.bintime.search.dto.CreateWaitlistRequest;
import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.service.WaitlistService;
import com.bintime.store.dto.CreateSlotRequest;
import com.bintime.store.dto.CreateStoreRequest;
import com.bintime.store.dto.CreateStoreServiceRequest;
import com.bintime.store.service.StoreApplicationService;
import com.bintime.user.security.BintimePrincipal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:notification-pipeline;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "bintime.store.expiration-enabled=false",
      "search.scheduling-enabled=false",
      "notification.scheduling-enabled=false",
      "notification.delivery.minimum-interval-seconds=0"
    })
@ActiveProfiles("test")
class NotificationPipelineTests {
  @Autowired DataSource source;
  @Autowired JdbcTemplate db;
  @Autowired JsonMapper json;
  @Autowired StoreApplicationService stores;
  @Autowired WaitlistService waitlists;
  @Autowired NotificationSubscriptions subscriptions;
  @Autowired NotificationService notifications;
  @Autowired NotificationDeliveryWorker worker;
  @MockitoBean FcmClient fcm;
  @MockitoBean com.bintime.search.service.NotificationSimilarityService similarity;

  @Test
  void realStoreAndWaitlistContractsDeliverOnceAndSuppressCanceledWaitlists() throws Exception {
    when(similarity.matches(anyString(), any())).thenReturn(true);
    // Only this isolated H2 schema receives fixtures; external providers are replaced at their
    // boundary.
    String schema =
        new ClassPathResource("notification-delivery-schema.sql")
            .getContentAsString(StandardCharsets.UTF_8);
    new ResourceDatabasePopulator(
            new ByteArrayResource(
                schema.substring(schema.indexOf(';') + 1).getBytes(StandardCharsets.UTF_8)))
        .execute(source);
    new ResourceDatabasePopulator(new ClassPathResource("notification-subscriptions-schema.sql"))
        .execute(source);
    db.update(
        "insert into user_accounts(user_id,display_name,role_code) values('owner','사장님','OWNER')");
    db.update(
        "insert into user_accounts(user_id,display_name,role_code)"
            + " values('customer','고객','CUSTOMER')");
    var owner = new Actor("owner", "OWNER", "사장님");
    var hours =
        List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN").stream()
            .map(
                day ->
                    Map.of(
                        "dayOfWeek", day, "opensAt", "09:00", "closesAt", "22:00", "closed", false))
            .toList();
    var store =
        stores.createStore(
            owner,
            "create-store",
            json.convertValue(
                Map.of(
                    "name",
                    "실제 계약 매장",
                    "category",
                    "NAIL",
                    "address",
                    "서울",
                    "location",
                    Map.of("latitude", 37.5, "longitude", 127.0),
                    "openingHours",
                    hours),
                CreateStoreRequest.class));
    var service =
        stores.createService(
            owner,
            store.id(),
            "create-service",
            new CreateStoreServiceRequest("젤 네일", 60, 50000L, true));
    var start =
        LocalDate.now(ZoneId.of("Asia/Seoul"))
            .plusDays(1)
            .atTime(12, 0)
            .atZone(ZoneId.of("Asia/Seoul"))
            .toOffsetDateTime();
    var first =
        stores.createSlot(
            owner,
            store.id(),
            "first-slot",
            json.convertValue(
                Map.of("serviceId", service.id(), "startAt", start), CreateSlotRequest.class));
    var second =
        stores.createSlot(
            owner,
            store.id(),
            "second-slot",
            json.convertValue(
                Map.of("serviceId", service.id(), "startAt", start.plusHours(2)),
                CreateSlotRequest.class));
    var waitlist =
        waitlists.create(
            "customer",
            "create-waitlist",
            new CreateWaitlistRequest(
                "MANUAL",
                new WaitlistConditions(
                    store.id(),
                    "NAIL",
                    service.id(),
                    start.minusHours(1),
                    start.plusHours(4),
                    null,
                    null,
                    60000L,
                    List.of()),
                true));
    var principal = new BintimePrincipal("customer", "CUSTOMER");
    var session = new MockHttpSession();
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
    session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    subscriptions.register(principal, session, "pipeline-fid");
    when(fcm.enabled()).thenReturn(true);
    when(fcm.send(eq("pipeline-fid"), any(), any(BooleanSupplier.class)))
        .thenAnswer(
            invocation -> {
              assertThat(invocation.<BooleanSupplier>getArgument(2).getAsBoolean()).isTrue();
              return new FcmClient.Result(true, null, false, 0);
            });
    var matches =
        List.of(new DispatchNotificationRequest.MatcheItem(waitlist.id(), waitlist.version()));
    var request =
        new DispatchNotificationRequest(
            "first-event", "customer", "VACANCY_MATCHED", first.id(), null, matches, null);
    var queued = notifications.enqueue(request);
    assertThat(queued.status()).isEqualTo("QUEUED");
    assertThat(notifications.enqueue(request).notificationId()).isEqualTo(queued.notificationId());
    worker.run();
    worker.run();
    verify(fcm).send(eq("pipeline-fid"), any(), any(BooleanSupplier.class));
    assertThat(notifications.list(principal, true, null, 20).items())
        .singleElement()
        .satisfies(item -> assertThat(item.availability()).isEqualTo("AVAILABLE"));
    notifications.read(principal, queued.notificationId(), true);
    assertThat(notifications.list(principal, true, null, 20).unreadCount()).isZero();

    notifications.enqueue(
        new DispatchNotificationRequest(
            "second-event", "customer", "VACANCY_MATCHED", second.id(), null, matches, null));
    waitlists.cancel(
        "customer",
        waitlist.id(),
        "cancel-waitlist",
        new CancelWaitlistRequest(waitlist.version()));
    worker.run();
    verify(fcm, times(1)).send(eq("pipeline-fid"), any(), any(BooleanSupplier.class));
    assertThat(
            db.queryForList(
                "select status from notification_deliveries order by status", String.class))
        .containsExactly("ACCEPTED", "SKIPPED");
    assertThat(db.queryForObject("select count(*) from notification_alerts", Integer.class))
        .isEqualTo(2);
    assertThat(db.queryForObject("select count(*) from notification_events", Integer.class))
        .isEqualTo(3);
  }
}
