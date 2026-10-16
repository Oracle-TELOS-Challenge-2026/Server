package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.bintime.search.adapter.ElasticsearchStoreAdapter;
import com.bintime.search.service.*;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.contract.SearchStoreCatalog.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "SEARCH_ELASTICSEARCH_URL", matches = ".+")
class SearchElasticsearchTests {
  @Test
  void nestedPriceVectorAndPitPaginationAgainstRealElasticsearch() throws Exception {
    String base = System.getenv("SEARCH_ELASTICSEARCH_URL"),
        alias = "bintime-search-it-" + UUID.randomUUID();
    String name = alias + "-v1";
    var json = JsonMapper.builder().build();
    var adapter = new ElasticsearchStoreAdapter(json, base, alias, "");
    var clock = Clock.fixed(Instant.parse("2026-10-16T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    var start = OffsetDateTime.parse("2026-10-17T15:00:00+09:00");
    var a =
        new Slot(
            "a",
            "store-a",
            "A",
            "NAIL",
            "svc",
            "네일",
            37.5,
            127,
            start.plusHours(1),
            start.plusHours(2),
            60000,
            50000,
            "VACANCY",
            1,
            1L,
            null,
            "네일");
    var cheapOutside =
        new Slot(
            "a-old",
            "store-a",
            "A",
            "NAIL",
            "svc",
            "네일",
            37.5,
            127,
            start.minusHours(5),
            start.minusHours(4),
            60000,
            100,
            "VACANCY",
            1,
            1L,
            null,
            "네일");
    var b =
        new Slot(
            "b",
            "store-b",
            "B",
            "NAIL",
            "svc",
            "네일",
            37.501,
            127,
            start,
            start.plusHours(1),
            60000,
            30000,
            "VACANCY",
            1,
            1L,
            null,
            "네일");
    var docA =
        new StoreDocument(
            "store-a", "A", "NAIL", null, null, null, 37.5, 127, "네일", 1, List.of(a, cheapOutside));
    var docB =
        new StoreDocument(
            "store-b", "B", "NAIL", null, null, null, 37.501, 127, "네일", 1, List.of(b));
    float[] positive = new float[1536], negative = new float[1536];
    positive[0] = 1;
    negative[0] = -1;
    try {
      adapter.create(name);
      adapter.upsert(name, docA, positive);
      adapter.upsert(name, docB, negative);
      adapter.activate(name);
      var query =
          new StoreSearchService.Query(
              37.5,
              127,
              3000,
              null,
              "NAIL",
              start,
              start.plusHours(3),
              true,
              "PRICE",
              OffsetDateTime.now(clock));
      String pit = adapter.open();
      var first = adapter.search(query, pit, List.of(), null, 1);
      assertThat(first.hits()).hasSize(1);
      assertThat(first.hits().getFirst().store().id()).isEqualTo("store-b");
      var second = adapter.search(query, first.pit(), first.hits().getFirst().sort(), null, 1);
      assertThat(second.hits().getFirst().store().id()).isEqualTo("store-a");
      adapter.close(second.pit());
      query =
          new StoreSearchService.Query(
              37.5,
              127,
              3000,
              null,
              "NAIL",
              start,
              start.plusHours(3),
              true,
              "RECOMMENDED",
              OffsetDateTime.now(clock));
      pit = adapter.open();
      var recommended = adapter.search(query, pit, List.of(), positive, 2);
      assertThat(recommended.hits().getFirst().store().id()).isEqualTo("store-a");
      adapter.close(recommended.pit());
      var catalog = mock(SearchStoreCatalog.class);
      when(catalog.revalidate(any())).thenReturn(List.of(a, b));
      var beans = new StaticListableBeanFactory();
      var service =
          new StoreSearchService(
              adapter,
              catalog,
              new SearchCursor(json, clock, "01234567890123456789012345678901"),
              clock,
              beans.getBeanProvider(PersonalizationService.class),
              json);
      var page =
          service.search(
              37.5,
              127,
              3000,
              null,
              "NAIL",
              start,
              start.plusHours(3),
              true,
              "PRICE",
              null,
              1,
              null);
      assertThat(page.items().getFirst().bestSlot().id()).isEqualTo("b");
      assertThat(page.page().hasNext()).isTrue();
      assertThatThrownBy(
              () ->
                  service.search(
                      37.5,
                      127,
                      3000,
                      null,
                      "NAIL",
                      start,
                      start.plusHours(3),
                      true,
                      "DISTANCE",
                      page.page().nextCursor(),
                      1,
                      null))
          .hasMessage("CURSOR_CONDITIONS_CHANGED");
      var tail =
          service.search(
              37.5,
              127,
              3000,
              null,
              "NAIL",
              start,
              start.plusHours(3),
              true,
              "PRICE",
              page.page().nextCursor(),
              1,
              null);
      assertThat(tail.items().getFirst().bestSlot().id()).isEqualTo("a");
      assertThat(tail.page().hasNext()).isFalse();
      when(catalog.revalidate(any())).thenReturn(List.of());
      assertThat(
              service
                  .search(
                      37.5,
                      127,
                      3000,
                      null,
                      "NAIL",
                      start,
                      start.plusHours(3),
                      true,
                      "PRICE",
                      null,
                      20,
                      null)
                  .items())
          .isEmpty();
    } finally {
      HttpClient.newHttpClient()
          .send(
              HttpRequest.newBuilder(URI.create(base + "/" + name)).DELETE().build(),
              HttpResponse.BodyHandlers.discarding());
    }
  }
}
