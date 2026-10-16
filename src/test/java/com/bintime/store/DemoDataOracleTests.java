package com.bintime.store;

import static org.assertj.core.api.Assertions.*;

import com.bintime.common.auth.Actor;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.search.port.IntentAiPort;
import com.bintime.search.port.StoreIndexPort;
import com.bintime.search.repository.SearchRepository;
import com.bintime.search.service.ConditionRules;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.dto.*;
import com.bintime.store.service.StoreApplicationService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** INSERT된 시연 데이터를 그대로 읽어 실제 서비스의 필터링·취소·할인·예약 경합을 검증한다. */
@SpringBootTest(
    properties = {
      "spring.sql.init.mode=never",
      "spring.jpa.hibernate.ddl-auto=validate",
      "notification.schema-validation-enabled=true"
    })
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "DEMO_ORACLE_URL", matches = ".+")
class DemoDataOracleTests {
  @Autowired StoreApplicationService stores;
  @Autowired SearchStoreCatalog catalog;
  @Autowired SearchRepository search;
  @Autowired ConditionRules rules;
  @Autowired JdbcTemplate db;
  @MockitoBean IntentAiPort ai;
  @MockitoBean StoreIndexPort index;

  @DynamicPropertySource
  static void isolatedOracle(DynamicPropertyRegistry props) {
    String user = System.getenv("DEMO_ORACLE_USER");
    String url = System.getenv("DEMO_ORACLE_URL");
    if (user == null
        || !user.matches("BT_DEMO_[A-F0-9]{12}")
        || !url.matches("jdbc:oracle:thin:@//localhost:[0-9]+/FREEPDB1"))
      throw new IllegalArgumentException("Demo tests require an isolated local BT_DEMO_ schema");
    props.add("spring.datasource.url", () -> url);
    props.add("spring.datasource.username", () -> user);
    props.add("spring.datasource.password", () -> System.getenv("DEMO_ORACLE_PASSWORD"));
    props.add("spring.datasource.driver-class-name", () -> "oracle.jdbc.OracleDriver");
  }

  @Test
  void seededScenarioSupportsFilteringCancellationDiscountAndOneWinner() throws Exception {
    var all = new ArrayList<com.bintime.search.model.Intent>();
    for (int n = 1; n <= 14; n++) all.add(search.find("demo_wait_%02d".formatted(n)).orElseThrow());
    for (var intent : all) {
      rules.normalize(intent.conditions(), Instant.now());
      catalog.validate(intent.conditions());
    }
    var anchor = all.get(1).conditions().location();
    assertThat(anchor.latitude()).isEqualTo(37.51335413975466);
    assertThat(anchor.longitude()).isEqualTo(126.92649449142719);
    for (int n = 1; n <= 6; n++) {
      var location =
          db.queryForMap(
              "select latitude,longitude from store_shops where store_id=?",
              "demo_shop_%02d".formatted(n));
      double meters =
          ConditionRules.distance(
              anchor.latitude(),
              anchor.longitude(),
              ((Number) location.get("LATITUDE")).doubleValue(),
              ((Number) location.get("LONGITUDE")).doubleValue());
      if (n <= 4) assertThat(meters).isLessThan(1500);
      else assertThat(meters).isGreaterThan(1500);
    }
    assertThat(catalog.available(all.get(8).conditions(), null, 100).items())
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly("demo_vac_open");
    assertThat(catalog.available(all.get(11).conditions(), null, 100).items())
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly("demo_slot_browse_04_care");
    assertThat(
            catalog.revalidate(
                List.of(
                    "demo_slot_reserved_01",
                    "demo_vac_withdrawn",
                    "demo_vac_expired_cancel",
                    "demo_slot_browse_06_art")))
        .isEmpty();

    var owner = new Actor("demo_owner_01", "OWNER", "데모 사장님 1");
    var canceled =
        stores.cancel(
            owner,
            "demo_reservation_01",
            "demo-cancel-main",
            new CancelReservationRequest("STORE_REQUEST", 1L, null));
    assertThat(canceled.reservation().status()).isEqualTo("CANCELED");
    assertThat(canceled.vacancy().status()).isEqualTo("DRAFT");
    var published =
        stores.publish(
            owner,
            "demo_shop_01",
            canceled.vacancy().id(),
            "demo-publish-main",
            new PublishVacancyRequest(
                "demo_service_01_gel",
                canceled.vacancy().availableFrom(),
                new PublishVacancyRequest.Discount("PERCENT", 30L),
                canceled.vacancy().version()));
    assertThat(published.slot().price().payable()).isEqualTo(42000L);
    var slot = catalog.revalidate(List.of(published.slot().id())).getFirst();
    assertThat(all.stream().filter(i -> rules.matches(i.conditions(), slot, Instant.now())))
        .extracting(com.bintime.search.model.Intent::id)
        .containsExactly(
            "demo_wait_01", "demo_wait_02", "demo_wait_03", "demo_wait_13", "demo_wait_14");
    // 13/14는 조건만 일치한다. 실제 알림은 별도 로그인·구독이 필요하며 이 테스트는 발송하지 않는다.
    var gate = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<Boolean>> results = new ArrayList<>();
      for (int n = 1; n <= 2; n++) {
        int customer = n;
        results.add(
            pool.submit(
                () -> {
                  gate.await();
                  try {
                    var reserved =
                        stores.reserve(
                            new Actor(
                                "demo_customer_%02d".formatted(customer),
                                "CUSTOMER",
                                customer == 1 ? "서윤" : "지우"),
                            "demo-race-" + customer,
                            new CreateReservationRequest(
                                slot.id(),
                                42000L,
                                slot.version(),
                                "demo_wait_%02d".formatted(customer)));
                    assertThat(reserved.status()).isEqualTo("CONFIRMED");
                    return true;
                  } catch (ApiBusinessException e) {
                    assertThat(e.code).isEqualTo("SLOT_UNAVAILABLE");
                    return false;
                  }
                }));
      }
      gate.countDown();
      int winners = 0;
      for (var result : results) if (result.get(30, TimeUnit.SECONDS)) winners++;
      assertThat(winners).isEqualTo(1);
    }
    assertThat(catalog.revalidate(List.of(slot.id()))).isEmpty();
    assertThat(
            db.queryForObject(
                "select count(*) from store_reservations where slot_id=? and"
                    + " status_code='CONFIRMED'",
                Integer.class,
                slot.id()))
        .isEqualTo(1);
    assertThat(
            db.queryForObject(
                "select count(*) from search_intent where id in ('demo_wait_01','demo_wait_02') and"
                    + " status='FULFILLED'",
                Integer.class))
        .isEqualTo(1);
  }
}
