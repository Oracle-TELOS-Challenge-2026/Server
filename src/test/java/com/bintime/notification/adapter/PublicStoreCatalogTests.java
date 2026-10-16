package com.bintime.notification.adapter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.common.dto.CursorPage;
import com.bintime.common.web.ApiBusinessException;
import com.bintime.search.dto.WaitlistConditions;
import com.bintime.search.service.ConditionRules;
import com.bintime.search.service.SearchFailure;
import com.bintime.store.contract.SearchStoreCatalog;
import com.bintime.store.dto.ReservationSlotListResponse;
import com.bintime.store.dto.ReservationSlotResponse;
import com.bintime.store.dto.StoreDetailResponse;
import com.bintime.store.dto.StoreServiceListResponse;
import com.bintime.store.dto.StoreServiceResponse;
import com.bintime.store.service.StoreApplicationService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

class PublicStoreCatalogTests {
  private final OffsetDateTime now = OffsetDateTime.parse("2026-10-16T01:00:00Z");
  private StoreApplicationService stores;
  private PlatformTransactionManager transactions;
  private PublicStoreCatalog catalog;

  @BeforeEach
  void setUp() {
    stores = mock(StoreApplicationService.class);
    transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any()))
        .thenAnswer(invocation -> new SimpleTransactionStatus());
    catalog =
        new PublicStoreCatalog(
            stores,
            new ConditionRules(),
            Clock.fixed(now.toInstant(), ZoneOffset.UTC),
            JsonMapper.builder().build(),
            transactions);
    when(stores.storeIds()).thenReturn(List.of("shop_b", "shop_a"));
    for (String id : List.of("shop_a", "shop_b")) {
      when(stores.getStore(id)).thenReturn(store(id, "NAIL"));
      when(stores.services(id, null, 100))
          .thenReturn(
              new StoreServiceListResponse(
                  List.of(service(id, "service_" + id, true)), new CursorPage(null, false)));
    }
    when(stores.getSlot(anyString(), anyString()))
        .thenThrow(new ApiBusinessException(404, "SLOT_NOT_FOUND"));
  }

  @Test
  void revalidationUsesPublicBookabilityAndPreservesVersionsAndPrices() {
    var regular = slot("shop_b", "regular", now.plusHours(2), true, "AVAILABLE", null, 8);
    var vacancy = slot("shop_a", "vacancy", now.plusHours(3), true, "AVAILABLE", 3L, 12);
    doReturn(regular).when(stores).getSlot("shop_b", "regular");
    doReturn(vacancy).when(stores).getSlot("shop_a", "vacancy");
    doReturn(slot("shop_a", "booked", now.plusHours(1), false, "BOOKED", 2L, 4))
        .when(stores)
        .getSlot("shop_a", "booked");
    doReturn(slot("shop_a", "inactive", now.plusHours(1), false, "AVAILABLE", null, 1))
        .when(stores)
        .getSlot("shop_a", "inactive");
    doReturn(slot("shop_a", "past", now.minusHours(1), true, "AVAILABLE", null, 1))
        .when(stores)
        .getSlot("shop_a", "past");
    var result =
        catalog.revalidate(
            List.of("regular", "vacancy", "booked", "inactive", "past", "missing", "regular"));
    assertThat(result)
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly("regular", "vacancy");
    assertThat(result.getFirst().publicationVersion()).isNull();
    assertThat(result.getFirst().version()).isEqualTo(8);
    assertThat(result.getLast().publicationVersion()).isEqualTo(3);
    assertThat(result.getLast().payablePrice()).isEqualTo(30000);
    assertThat(result.getLast().serviceName()).isEqualTo("Service shop_a");
    verify(transactions, atLeastOnce()).rollback(any());
    ArgumentCaptor<TransactionDefinition> definitions =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactions, atLeastOnce()).getTransaction(definitions.capture());
    assertThat(definitions.getAllValues())
        .allMatch(
            d -> d.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Test
  void availableFiltersAllStoresAndUsesStableConditionBoundPagination() {
    var a1 = slot("shop_a", "a_1", now.plusHours(1), true, "AVAILABLE", null, 1);
    var a2 = slot("shop_a", "a_2", now.plusHours(2), true, "AVAILABLE", 2L, 2);
    var b1 = slot("shop_b", "b_1", now.plusHours(3), true, "AVAILABLE", null, 1);
    when(stores.slots(eq("shop_a"), any(), any(), isNull(), isNull(), eq(100)))
        .thenReturn(page(List.of(a2, a1)));
    when(stores.slots(eq("shop_b"), any(), any(), isNull(), isNull(), eq(100)))
        .thenReturn(page(List.of(b1)));
    WaitlistConditions conditions = conditions(null, null, now.plusDays(1), 40000L);
    var first = catalog.available(conditions, null, 1);
    assertThat(first.items()).extracting(SearchStoreCatalog.Slot::id).containsExactly("a_1");
    var second = catalog.available(conditions, first.nextCursor(), 2);
    assertThat(second.items())
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly("a_2", "b_1");
    assertThat(second.nextCursor()).isNull();
    assertThatThrownBy(
            () ->
                catalog.available(
                    conditions("shop_b", null, now.plusDays(1), 40000L), first.nextCursor(), 1))
        .isInstanceOfSatisfying(
            SearchFailure.class, failure -> assertThat(failure.code).isEqualTo("INVALID_CURSOR"));
    assertThat(catalog.available(conditions(null, null, now.plusDays(1), 20000L), null, 10).items())
        .isEmpty();
    assertThat(
            catalog
                .available(conditions("shop_b", null, now.plusDays(1), 40000L), null, 10)
                .items())
        .extracting(SearchStoreCatalog.Slot::storeId)
        .containsExactly("shop_b");
  }

  @Test
  void availableFollowsEveryPublicPageAndSegmentsLongWindowsWithoutTruncation() {
    var firstDay = now.atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul")).toLocalDate();
    var first = slot("shop_a", "early", now.plusHours(1), true, "AVAILABLE", null, 1);
    var next = slot("shop_a", "late", now.plusDays(40), true, "AVAILABLE", null, 1);
    when(stores.slots("shop_a", firstDay, firstDay.plusDays(30), null, null, 100))
        .thenReturn(
            new ReservationSlotListResponse(List.of(first), new CursorPage("store-page-2", true)));
    when(stores.slots("shop_a", firstDay, firstDay.plusDays(30), null, "store-page-2", 100))
        .thenReturn(page(List.of()));
    when(stores.slots("shop_a", firstDay.plusDays(31), firstDay.plusDays(45), null, null, 100))
        .thenReturn(page(List.of(next)));
    var result = catalog.available(conditions("shop_a", null, now.plusDays(45), null), null, 100);
    assertThat(result.items())
        .extracting(SearchStoreCatalog.Slot::id)
        .containsExactly("early", "late");
    verify(stores).slots("shop_a", firstDay, firstDay.plusDays(30), null, "store-page-2", 100);
    verify(stores).slots("shop_a", firstDay.plusDays(31), firstDay.plusDays(45), null, null, 100);
  }

  @Test
  void validateAndDescribeCheckStoreCategoryServiceOwnershipAndActiveState() {
    catalog.validate(conditions("shop_a", "service_shop_a", now.plusDays(1), null));
    var references = catalog.describe(null, "service_shop_b");
    assertThat(references.store().id()).isEqualTo("shop_b");
    assertThat(references.service().name()).isEqualTo("Service shop_b");
    assertThat(references.service().imageUrl()).isNull();
    assertThat(catalog.describe(null, null))
        .isEqualTo(new SearchStoreCatalog.References(null, null));
    assertThatThrownBy(
            () -> catalog.validate(conditions("shop_a", "service_shop_b", now.plusDays(1), null)))
        .isInstanceOfSatisfying(
            SearchFailure.class,
            failure -> assertThat(failure.code).isEqualTo("SERVICE_NOT_FOUND"));
    when(stores.services("shop_a", null, 100))
        .thenReturn(
            new StoreServiceListResponse(
                List.of(service("shop_a", "service_shop_a", false)), new CursorPage(null, false)));
    assertThatThrownBy(
            () -> catalog.validate(conditions("shop_a", "service_shop_a", now.plusDays(1), null)))
        .isInstanceOfSatisfying(
            SearchFailure.class, failure -> assertThat(failure.code).isEqualTo("SERVICE_INACTIVE"));
    when(stores.getStore("shop_a")).thenReturn(store("shop_a", "HAIR"));
    assertThatThrownBy(() -> catalog.validate(conditions("shop_a", null, now.plusDays(1), null)))
        .isInstanceOfSatisfying(
            SearchFailure.class,
            failure -> assertThat(failure.code).isEqualTo("STORE_CATEGORY_MISMATCH"));
  }

  @Test
  void fullIndexExportFailsExplicitlyUntilStoreProvidesAnUnboundedVersionedSnapshot() {
    assertThatThrownBy(() -> catalog.export(null, 100))
        .isInstanceOfSatisfying(
            SearchFailure.class, failure -> assertThat(failure.status.value()).isEqualTo(503));
    verifyNoInteractions(stores);
  }

  private ReservationSlotListResponse page(List<ReservationSlotResponse> values) {
    return new ReservationSlotListResponse(values, new CursorPage(null, false));
  }

  private WaitlistConditions conditions(
      String store, String service, OffsetDateTime end, Long price) {
    return new WaitlistConditions(
        store,
        "NAIL",
        service,
        now,
        end,
        new WaitlistConditions.Location(37.5, 127.0),
        10000,
        price,
        List.of());
  }

  private StoreDetailResponse store(String id, String category) {
    return new StoreDetailResponse(
        id,
        "Store " + id,
        category,
        "Shop description",
        "Address",
        new StoreDetailResponse.Location(37.5, 127.0),
        "https://example.com/shop.jpg",
        new StoreDetailResponse.Rating(4.5, 2),
        "Asia/Seoul",
        List.of(),
        9L);
  }

  private StoreServiceResponse service(String store, String id, boolean active) {
    return new StoreServiceResponse(
        id, store, "NAIL", "Service " + store, 60, 50000L, "KRW", active, 2L);
  }

  private ReservationSlotResponse slot(
      String store,
      String id,
      OffsetDateTime start,
      boolean bookable,
      String status,
      Long publication,
      long version) {
    return new ReservationSlotResponse(
        id,
        store,
        "service_" + store,
        null,
        start,
        start.plusHours(1),
        status,
        bookable,
        bookable ? null : status,
        publication == null ? "REGULAR" : "VACANCY",
        null,
        publication,
        new ReservationSlotResponse.Price(50000L, 30000L, null, "KRW"),
        version);
  }
}
