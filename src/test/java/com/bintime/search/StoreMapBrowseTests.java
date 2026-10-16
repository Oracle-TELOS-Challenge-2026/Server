package com.bintime.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.search.port.StoreIndexPort;
import com.bintime.search.service.*;
import com.bintime.store.contract.SearchStoreCatalog;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

class StoreMapBrowseTests {
  StoreIndexPort index = mock(StoreIndexPort.class);
  SearchStoreCatalog catalog = mock(SearchStoreCatalog.class);
  Clock clock = Clock.fixed(Instant.parse("2026-10-17T01:00:00Z"), ZoneOffset.UTC);
  StoreSearchService search;

  @BeforeEach
  void setup() {
    var json = JsonMapper.builder().build();
    search =
        new StoreSearchService(
            index,
            catalog,
            new SearchCursor(json, clock, "01234567890123456789012345678901"),
            clock,
            new StaticListableBeanFactory().getBeanProvider(PersonalizationService.class),
            json);
  }

  private SearchStoreCatalog.StoreDocument store(String id, String category, double latitude) {
    return new SearchStoreCatalog.StoreDocument(
        id, "새 매장 " + id, category, null, 0d, 0, latitude, 127, "조용한 네일", 1, List.of());
  }

  @Test
  void includesStoresWithoutServicesOrSlotsWithoutCallingElasticsearch() {
    when(catalog.export(null, 100))
        .thenReturn(new SearchStoreCatalog.StorePage(List.of(store("new", "NAIL", 37.5)), null));
    var response =
        search.search(37.5, 127, 3000, null, null, null, null, false, "DISTANCE", null, 20, null);
    assertThat(response.items()).hasSize(1);
    assertThat(response.items().getFirst().store().id()).isEqualTo("new");
    assertThat(response.items().getFirst().bestSlot()).isNull();
    assertThat(response.page().hasNext()).isFalse();
    verifyNoInteractions(index);
  }

  @Test
  void respectsExplicitMapFiltersAndReadsEveryCatalogPage() {
    when(catalog.export(null, 100))
        .thenReturn(
            new SearchStoreCatalog.StorePage(
                List.of(store("far", "NAIL", 38.5), store("hair", "HAIR", 37.5)), "next"));
    when(catalog.export("next", 100))
        .thenReturn(new SearchStoreCatalog.StorePage(List.of(store("near", "NAIL", 37.5)), null));
    var response =
        search.search(
            37.5, 127, 3000, "조용한", "NAIL", null, null, false, "DISTANCE", null, 20, null);
    assertThat(response.items()).extracting(item -> item.store().id()).containsExactly("near");
    verifyNoInteractions(index);
  }

  @Test
  void mapCursorPagesEqualDistancesWithoutDuplicatesAndRejectsChangedFilters() {
    when(catalog.export(null, 100))
        .thenReturn(
            new SearchStoreCatalog.StorePage(
                List.of(store("b", "NAIL", 37.5), store("a", "NAIL", 37.5)), null));
    var first =
        search.search(37.5, 127, 3000, null, null, null, null, false, "DISTANCE", null, 1, null);
    assertThat(first.items().getFirst().store().id()).isEqualTo("a");
    assertThat(first.page().hasNext()).isTrue();
    String cursor = first.page().nextCursor();
    var second =
        search.search(37.5, 127, 3000, null, null, null, null, false, "DISTANCE", cursor, 1, null);
    assertThat(second.items()).extracting(item -> item.store().id()).containsExactly("b");
    assertThat(second.page().hasNext()).isFalse();
    assertThatThrownBy(
            () ->
                search.search(
                    37.5, 127, 2000, null, null, null, null, false, "DISTANCE", cursor, 1, null))
        .hasMessage("CURSOR_CONDITIONS_CHANGED");
    verifyNoInteractions(index);
  }
}
