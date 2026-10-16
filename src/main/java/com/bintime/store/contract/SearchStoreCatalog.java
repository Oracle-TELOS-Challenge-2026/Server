package com.bintime.store.contract;

import com.bintime.search.dto.WaitlistConditions;
import java.time.OffsetDateTime;
import java.util.*;

/** store 원장의 공개 계약. cursor 순회는 누락 없이 완료되어야 한다. */
public interface SearchStoreCatalog {
  void validate(WaitlistConditions conditions);

  Page available(WaitlistConditions conditions, String cursor, int limit);

  List<Slot> revalidate(Collection<String> slotIds);

  StorePage export(String cursor, int limit);

  References describe(String storeId, String serviceId);

  record References(Reference store, Reference service) {}

  record Reference(String id, String name, String imageUrl) {}

  record Page(List<Slot> items, String nextCursor) {}

  record StorePage(List<StoreDocument> items, String nextCursor) {}

  /** description은 현재 슬롯과 무관한 실제 매장/전체 시술 설명이다. version은 검색 관련 변경마다 증가한다. */
  record StoreDocument(
      String id,
      String name,
      String category,
      String imageUrl,
      Double rating,
      Integer ratingCount,
      double latitude,
      double longitude,
      String description,
      long version,
      List<Slot> slots) {}

  record Slot(
      String id,
      String storeId,
      String storeName,
      String category,
      String serviceId,
      String serviceName,
      double latitude,
      double longitude,
      OffsetDateTime startAt,
      OffsetDateTime endAt,
      long originalPrice,
      long payablePrice,
      String source,
      long version,
      Long publicationVersion,
      String imageUrl,
      String description) {}
}
