package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.notification.dto.DispatchNotificationRequest;
import com.bintime.search.dto.*;
import com.bintime.search.port.*;
import com.bintime.search.repository.SearchRepository;
import com.bintime.search.service.*;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.*;
import com.bintime.user.contract.SearchUserAccess;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

class SearchBehaviorTests {
  MutableClock clock;
  SearchRepository db;
  WaitlistService service;
  MatchService matching;
  SearchWorker worker;
  SearchStoreCatalog catalog;
  IntentAiPort ai;
  SearchUserAccess users;
  List<DispatchNotificationRequest> sent;

  @BeforeEach
  void setup() {
    clock = new MutableClock();
    var source =
        new DriverManagerDataSource(
            "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
    new ResourceDatabasePopulator(new ClassPathResource("search-schema.sql")).execute(source);
    var json = JsonMapper.builder().build();
    db = new SearchRepository(new JdbcTemplate(source), json);
    var tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    var rules = new ConditionRules();
    catalog = mock(SearchStoreCatalog.class);
    when(catalog.describe(any(), any())).thenReturn(new SearchStoreCatalog.References(null, null));
    ai = mock(IntentAiPort.class);
    users = mock(SearchUserAccess.class);
    sent = new CopyOnWriteArrayList<>();
    when(catalog.available(any(), any(), anyInt())).thenReturn(new Page(List.of(slot()), null));
    when(catalog.revalidate(any())).thenReturn(List.of(slot()));
    when(users.notificationsEnabled(anyString())).thenReturn(true);
    when(users.explicitPreferences(anyString())).thenReturn("원컬러");
    when(ai.embed(any())).thenThrow(SearchFailure.unavailable());
    service = new WaitlistService(db, rules, catalog, clock, tx);
    matching = new MatchService(db, tx, clock);
    var vectors = new PersonalizationService(db, ai, users, tx, clock, "text-embedding-3-small");
    worker =
        new SearchWorker(
            db,
            tx,
            catalog,
            rules,
            r -> {
              if (sent.stream()
                  .noneMatch(x -> x.userId().equals(r.userId()) && x.slotId().equals(r.slotId())))
                sent.add(r);
            },
            users,
            clock,
            vectors,
            mock(SearchIndexService.class));
  }

  WaitlistConditions conditions(int minute) {
    return new WaitlistConditions(
        null,
        "NAIL",
        null,
        OffsetDateTime.parse("2026-10-17T15:00:00+09:00").plusMinutes(minute),
        OffsetDateTime.parse("2026-10-17T18:00:00+09:00"),
        new WaitlistConditions.Location(37.5, 127.0),
        3000,
        60000L,
        List.of("원컬러"));
  }

  CreateWaitlistRequest request(int minute) {
    return new CreateWaitlistRequest("MANUAL", conditions(minute), true);
  }

  Slot slot() {
    return new Slot(
        "slot",
        "store",
        "매장",
        "NAIL",
        "service",
        "원컬러",
        37.5,
        127.0,
        OffsetDateTime.parse("2026-10-17T16:00:00+09:00"),
        OffsetDateTime.parse("2026-10-17T17:00:00+09:00"),
        60000,
        60000,
        "VACANCY",
        1,
        1L,
        null,
        "원컬러 네일");
  }

  @Test
  void consentAndRequiredNullablePresence() {
    assertThatThrownBy(
            () ->
                service.create("u", "a", new CreateWaitlistRequest("MANUAL", conditions(0), false)))
        .isInstanceOf(SearchFailure.class)
        .hasMessage("NOTIFICATION_CONSENT_REQUIRED");
    var json = JsonMapper.builder().build();
    String body = db.encode(conditions(0));
    assertThat(json.readValue(body, WaitlistConditions.class)).isNotNull();
    assertThatThrownBy(
            () -> json.readValue(body.replace("\"maxPrice\":60000,", ""), WaitlistConditions.class))
        .isInstanceOf(Exception.class);
  }

  @Test
  void normalizedDuplicatesAndIdempotency() {
    var first = service.create("u", "key", request(0));
    assertThat(service.create("u", "key", request(0))).isEqualTo(first);
    assertThatThrownBy(() -> service.create("u", "key", request(1)))
        .hasMessage("IDEMPOTENCY_CONFLICT");
    assertThatThrownBy(() -> service.create("u", "different", request(0)))
        .hasMessage("DUPLICATE_WAITLIST");
    assertThat(db.jdbc().queryForObject("select count(*) from search_work", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void versionsOwnershipAndRepeatedCancellation() {
    var first = service.create("u", "key", request(0));
    assertThatThrownBy(() -> service.get("other", first.id())).hasMessage("WAITLIST_NOT_FOUND");
    var changed =
        service.update("u", first.id(), new UpdateWaitlistRequest(conditions(1), 1L, true));
    assertThat(changed.version()).isEqualTo(2);
    assertThatThrownBy(() -> service.cancel("u", first.id(), "c", new CancelWaitlistRequest(1L)))
        .hasMessage("VERSION_CONFLICT");
    var canceled = service.cancel("u", first.id(), "c", new CancelWaitlistRequest(2L));
    assertThat(service.cancel("u", first.id(), "d", new CancelWaitlistRequest(1L)).status())
        .isEqualTo("CANCELED");
    assertThat(canceled.availability()).isEqualTo("CLOSED");
  }

  @Test
  void expiryAndReservationCompletion() {
    var first = service.create("u", "key", request(0));
    clock.now = Instant.parse("2026-10-17T09:00:00Z");
    assertThat(service.get("u", first.id()).status()).isEqualTo("EXPIRED");
    assertThatThrownBy(() -> service.fulfilled("u", first.id(), 1, "reservation"))
        .isInstanceOf(SearchFailure.class);
  }

  @Test
  void sameInstantAndFullDurationBoundaries() {
    var rules = new ConditionRules();
    var c = conditions(0);
    var offset =
        new WaitlistConditions(
            null,
            "NAIL",
            null,
            c.windowStart().withOffsetSameInstant(ZoneOffset.UTC),
            c.windowEnd().withOffsetSameInstant(ZoneOffset.UTC),
            c.location(),
            3000,
            60000L,
            List.of());
    assertThat(rules.matches(offset, slot(), clock.instant())).isTrue();
    var shortWindow =
        new WaitlistConditions(
            null,
            "NAIL",
            null,
            c.windowStart(),
            slot().endAt().minusSeconds(1),
            c.location(),
            3000,
            59999L,
            List.of());
    assertThat(rules.matches(shortWindow, slot(), clock.instant())).isFalse();
  }

  @Test
  void simultaneousDuplicateAndTwentyLimit() throws Exception {
    try (var pool = Executors.newFixedThreadPool(8)) {
      var tasks = new ArrayList<Callable<Boolean>>();
      for (int n = 0; n < 30; n++) {
        int m = n;
        tasks.add(
            () -> {
              try {
                service.create("u", "k" + m, request(0));
                return true;
              } catch (SearchFailure e) {
                return false;
              }
            });
      }
      long created =
          pool.invokeAll(tasks).stream()
              .filter(
                  f -> {
                    try {
                      return f.get();
                    } catch (Exception e) {
                      throw new RuntimeException(e);
                    }
                  })
              .count();
      assertThat(created).isEqualTo(1);
      tasks.clear();
      for (int n = 1; n < 30; n++) {
        int m = n;
        tasks.add(
            () -> {
              try {
                service.create("u", "other" + m, request(m));
                return true;
              } catch (SearchFailure e) {
                return false;
              }
            });
      }
      pool.invokeAll(tasks);
      assertThat(service.list("u", "ACTIVE", null, 100).items()).hasSize(20);
    }
  }

  void runKind(String kind) {
    for (String id :
        db.jdbc()
            .queryForList(
                "select id from search_work where kind=? and status='QUEUED'", String.class, kind))
      worker.run(id);
  }

  @Test
  void allCustomersAndMultipleIntentsAreDeliveredOnce() {
    for (int n = 0; n < 7; n++) service.create("u" + n, "k", request(0));
    service.create("u0", "k2", request(1));
    var r = new CreateMatchJobRequest("event", "slot", 1L);
    assertThat(matching.submit(r)).isEqualTo(matching.submit(r));
    assertThatThrownBy(() -> matching.submit(new CreateMatchJobRequest("event", "slot", 2L)))
        .hasMessage("EVENT_CONFLICT");
    runKind("SLOT");
    runKind("NOTIFY");
    assertThat(sent).hasSize(7);
    assertThat(
            sent.stream().filter(x -> x.userId().equals("u0")).findFirst().orElseThrow().matches())
        .hasSize(2);
    verify(ai, never()).embed(any());
  }

  @Test
  void changedOrCanceledIntentIsNotSentAndOldOffersDisappear() {
    var first = service.create("u", "k", request(0));
    runKind("INTENT");
    assertThat(service.get("u", first.id()).offers()).hasSize(1);
    service.cancel("u", first.id(), "cancel", new CancelWaitlistRequest(1L));
    runKind("NOTIFY");
    assertThat(sent).isEmpty();
    assertThat(service.get("u", first.id()).offers()).isEmpty();
  }

  @Test
  void temporaryFailureRetriesAndCrashedLeaseIsReclaimed() {
    matching.submit(new CreateMatchJobRequest("event", "slot", 1L));
    when(catalog.revalidate(any())).thenThrow(SearchFailure.unavailable());
    runKind("SLOT");
    assertThat(
            db.jdbc()
                .queryForObject(
                    "select attempts from search_work where kind='SLOT'", Integer.class))
        .isEqualTo(1);
    db.jdbc().update("update search_work set status='RUNNING',available_at=0 where kind='SLOT'");
    doReturn(List.of(slot())).when(catalog).revalidate(any());
    worker.poll();
    assertThat(db.jdbc().queryForObject("select status from search_match_job", String.class))
        .isEqualTo("SUCCEEDED");
  }

  @Test
  void analysisRejectsInventedLocationAndReportsOutage() {
    var c =
        new PartialIntentConditions(
            "fake",
            "NAIL",
            "fake",
            conditions(0).windowStart(),
            conditions(0).windowEnd(),
            new PartialIntentConditions.Location(1.0, 2.0),
            3000,
            60000L,
            List.of());
    when(ai.analyze(any(), any())).thenReturn(new IntentAiPort.Extraction(true, c));
    var analysis = new IntentAnalysisService(ai, clock, new ConditionRules(), catalog);
    var result = analysis.analyze(new AnalyzeIntentRequest("내일 네일", null));
    assertThat(result.conditions().storeId()).isNull();
    assertThat(result.conditions().location()).isNull();
    assertThat(result.resolution()).isEqualTo("NEEDS_CLARIFICATION");
    when(ai.analyze(any(), any())).thenThrow(SearchFailure.unavailable());
    assertThatThrownBy(() -> analysis.analyze(new AnalyzeIntentRequest("네일", null)))
        .hasMessage("SEARCH_DEPENDENCY_UNAVAILABLE");
  }

  @Test
  void signedCursorRejectsTamperingAndHasFixedExpiry() {
    var cursor =
        new SearchCursor(JsonMapper.builder().build(), clock, "01234567890123456789012345678901");
    var state =
        new SearchCursor.State(
            "pit", List.of(1, "id"), "hash", clock.millis() + 300000, clock.millis());
    String encoded = cursor.encode(state);
    assertThat(cursor.decode(encoded).pit()).isEqualTo("pit");
    assertThatThrownBy(() -> cursor.decode("x" + encoded)).hasMessage("INVALID_CURSOR");
    clock.now = clock.now.plusSeconds(300);
    assertThatThrownBy(() -> cursor.decode(encoded)).hasMessage("CURSOR_EXPIRED");
  }

  @Test
  void allEightHttpEndpointsAndFailureEnvelopes() throws Exception {
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("waitlist", service);
    beans.addBean("matching", matching);
    var index = mock(StoreIndexPort.class);
    when(index.open()).thenReturn("pit");
    when(index.search(any(), anyString(), any(), any(), anyInt()))
        .thenReturn(new StoreIndexPort.Page("pit", List.of()));
    var json = JsonMapper.builder().build();
    var search =
        new StoreSearchService(
            index,
            catalog,
            new SearchCursor(json, clock, "01234567890123456789012345678901"),
            clock,
            beans.getBeanProvider(PersonalizationService.class),
            json);
    beans.addBean("search", search);
    var partial =
        new PartialIntentConditions(null, "NAIL", null, null, null, null, null, null, List.of());
    when(ai.analyze(any(), any())).thenReturn(new IntentAiPort.Extraction(true, partial));
    var analysis = new IntentAnalysisService(ai, clock, new ConditionRules(), catalog);
    when(users.customer(any(), any(), anyBoolean()))
        .thenAnswer(
            a -> {
              if (a.getArgument(2, Boolean.class) && !"csrf".equals(a.getArgument(1)))
                throw new SearchFailure(
                    org.springframework.http.HttpStatus.FORBIDDEN, "CSRF_REQUIRED");
              return "http-user";
            });
    var mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new com.bintime.search.controller.WaitlistController(
                    beans.getBeanProvider(WaitlistService.class)),
                new com.bintime.search.controller.SearchController(
                    beans.getBeanProvider(StoreSearchService.class), analysis),
                new com.bintime.search.controller.InternalSearchController(
                    beans.getBeanProvider(MatchService.class)))
            .addInterceptors(new com.bintime.search.config.SearchHttpGuard(users, "internal"))
            .setControllerAdvice(
                new com.bintime.common.web.ApiExceptionHandler(),
                new com.bintime.common.web.ApiResponseAdvice(json))
            .build();
    var result =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/v1/search/waitlists")
                    .header("X-CSRF-Token", "csrf")
                    .header("Idempotency-Key", "http")
                    .contentType("application/json")
                    .content(db.encode(request(0))))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .isCreated())
            .andReturn();
    String id =
        json.readTree(result.getResponse().getContentAsString()).at("/success/data/id").asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/search/waitlists/me"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/api/v1/search/waitlists/" + id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                    "/api/v1/search/waitlists/" + id)
                .header("X-CSRF-Token", "csrf")
                .contentType("application/json")
                .content(db.encode(new UpdateWaitlistRequest(conditions(1), 1L, true))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/search/waitlists/" + id + "/cancel")
                .header("X-CSRF-Token", "csrf")
                .header("Idempotency-Key", "cancel-http")
                .contentType("application/json")
                .content("{\"expectedVersion\":2}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/search/intents/analyze")
                .header("X-CSRF-Token", "csrf")
                .contentType("application/json")
                .content("{\"text\":\"내일 네일\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/v1/search/stores")
                .param("latitude", "37.5")
                .param("longitude", "127"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    var event =
        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/api/v1/internal/search/match-jobs")
            .contentType("application/json")
            .content(db.encode(new CreateMatchJobRequest("http-event", "slot", 1L)));
    mvc.perform(event)
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
    mvc.perform(event.header("X-Internal-Token", "internal"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isAccepted());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/search/waitlists")
                .header("X-CSRF-Token", "csrf")
                .header("Idempotency-Key", "missing-consent")
                .contentType("application/json")
                .content(db.encode(request(0)).replace(",\"notificationConsent\":true", "")))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isBadRequest());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/search/intents/analyze")
                .contentType("application/json")
                .content("{\"text\":\"네일\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                .isForbidden());
  }

  @Test
  void oldDeliveryCannotUseNewVersionEvenWhenConditionsStillMatch() {
    var first = service.create("u", "k", request(0));
    runKind("INTENT");
    service.update("u", first.id(), new UpdateWaitlistRequest(conditions(1), 1L, true));
    runKind("NOTIFY");
    assertThat(sent).isEmpty();
  }

  @Test
  void oldUserVectorResponseCannotOverwriteChangedIntent() {
    var first = service.create("u", "k", request(0));
    var tx = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    var personalization =
        new PersonalizationService(db, ai, users, tx, clock, "text-embedding-3-small");
    doAnswer(
            a -> {
              service.update("u", first.id(), new UpdateWaitlistRequest(conditions(1), 1L, true));
              return new float[1536];
            })
        .when(ai)
        .embed(any());
    personalization.refresh("u");
    assertThat(db.jdbc().queryForObject("select count(*) from search_vector", Integer.class))
        .isZero();
    doReturn(new float[1536]).when(ai).embed(any());
    personalization.refresh("u");
    assertThat(personalization.vector("u")).hasSize(1536);
  }

  @Test
  void storeProjectionIsVersionedAndDoesNotEmbedForSlotOnlyChanges() {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(db.jdbc().getDataSource()));
    var index = mock(StoreIndexPort.class);
    var projection =
        new SearchIndexService(
            index, catalog, ai, clock, db, tx, "bintime-stores", "text-embedding-3-small");
    var doc =
        new StoreDocument(
            "store", "매장", "NAIL", null, null, null, 37.5, 127, "원컬러", 1, List.of(slot()));
    projection.changed(doc);
    var newer =
        new StoreDocument(
            "store", "매장", "NAIL", null, null, null, 37.5, 127, "원컬러", 2, List.of(slot()));
    projection.changed(newer);
    projection.changed(doc);
    assertThat(
            db.jdbc()
                .queryForObject("select source_version from search_store_projection", Long.class))
        .isEqualTo(2);
    assertThat(
            db.jdbc()
                .queryForObject(
                    "select count(*) from search_work where kind='STORE_VECTOR'", Integer.class))
        .isEqualTo(1);
    verify(ai, never()).embed(any());
    doAnswer(
            a -> {
              projection.changed(
                  new StoreDocument(
                      "store",
                      "매장",
                      "NAIL",
                      null,
                      null,
                      null,
                      37.5,
                      127,
                      "변경된 설명",
                      3,
                      List.of(slot())));
              return new float[1536];
            })
        .when(ai)
        .embed(any());
    projection.embed("store");
    assertThat(
            db.jdbc()
                .queryForObject("select vector_hash from search_store_projection", String.class))
        .isNull();
  }

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-10-16T00:00:00Z");

    public ZoneId getZone() {
      return ZoneId.of("Asia/Seoul");
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }
}
