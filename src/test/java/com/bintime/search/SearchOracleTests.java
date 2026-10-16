package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.search.dto.*;
import com.bintime.search.repository.SearchRepository;
import com.bintime.search.service.*;
import com.bintime.store.contract.SearchStoreCatalog;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "SEARCH_ORACLE_URL", matches = ".+")
class SearchOracleTests {
  @Test
  void oracleClobLockLimitEventAndIdempotency() throws Exception {
    var source =
        new DriverManagerDataSource(
            System.getenv("SEARCH_ORACLE_URL"),
            System.getenv("SEARCH_ORACLE_USER"),
            System.getenv("SEARCH_ORACLE_PASSWORD"));
    new ResourceDatabasePopulator(new FileSystemResource("docs/sql/search-oracle.sql"))
        .execute(source);
    var db = new SearchRepository(new JdbcTemplate(source), JsonMapper.builder().build());
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    var catalog = mock(SearchStoreCatalog.class);
    when(catalog.describe(any(), any())).thenReturn(new SearchStoreCatalog.References(null, null));
    var service = new WaitlistService(db, new ConditionRules(), catalog, clock, tx);
    var start = OffsetDateTime.parse("2026-10-17T15:00:00+09:00");
    try (var pool = Executors.newFixedThreadPool(6)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int n = 0; n < 30; n++) {
        int minute = n;
        tasks.add(
            () -> {
              var c =
                  new WaitlistConditions(
                      "store",
                      "NAIL",
                      null,
                      start.plusMinutes(minute),
                      start.plusHours(3),
                      null,
                      null,
                      null,
                      List.of());
              try {
                service.create(
                    "oracle-user", "k" + minute, new CreateWaitlistRequest("MANUAL", c, true));
                return true;
              } catch (SearchFailure e) {
                assertThat(e.code).isEqualTo("ACTIVE_WAITLIST_LIMIT");
                return false;
              }
            });
      }
      var results = pool.invokeAll(tasks);
      int successes = 0;
      for (var result : results) if (result.get()) successes++;
      assertThat(successes).isEqualTo(20);
    }
    assertThat(db.activeUsers("", 100)).containsExactly("oracle-user");
    var first = service.list("oracle-user", "ACTIVE", null, 100).items().getFirst();
    var cancel = new CancelWaitlistRequest(first.version());
    assertThat(service.cancel("oracle-user", first.id(), "cancel", cancel).status())
        .isEqualTo("CANCELED");
    assertThat(service.cancel("oracle-user", first.id(), "cancel", cancel).status())
        .isEqualTo("CANCELED");
    var matches = new MatchService(db, tx, clock);
    var event = new CreateMatchJobRequest("event", "slot", 1L);
    assertThat(matches.submit(event)).isEqualTo(matches.submit(event));
    assertThatThrownBy(() -> matches.submit(new CreateMatchJobRequest("event", "other", 1L)))
        .hasMessage("EVENT_CONFLICT");
  }
}
