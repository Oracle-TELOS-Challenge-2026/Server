package com.bintime.store.contract;

import com.bintime.search.dto.WaitlistConditions;
import java.time.OffsetDateTime;
import java.util.*;

/** store 원장의 공개 계약. cursor 순회는 누락 없이 완료되어야 한다. */
public interface SearchStoreCatalog {
  /** 매장·시술 참조를 검증한다. 현재 공급이 없어도 유효한 대기는 허용한다. */
  void validate(WaitlistConditions conditions);

  /** 시작 시각·ID 오름차순. 전체 시술 시간·가격·거리 조건을 만족하는 미래 공급만 반환한다. */
  Page available(WaitlistConditions conditions, String cursor, int limit);

  /** 예약·철회·차단·만료·비활성 및 확정 예약과 겹치는 공급은 반환하지 않는다. */
  List<Slot> revalidate(Collection<String> slotIds);

  /** 매장 ID 오름차순. 가용 공급이 없는 매장도 포함해 검색 인덱스의 오래된 공급을 지울 수 있다. */
  StorePage export(String cursor, int limit);

  References describe(String storeId, String serviceId);

  record References(Reference store, Reference service) {}

  record Reference(String id, String name, String imageUrl) {}

  record Page(List<Slot> items, String nextCursor) {}

  record StorePage(List<StoreDocument> items, String nextCursor) {}

  /** description은 매장/전체 시술 설명이다. version은 PATCH용 버전과 별개인 store_shops.search_version이다. */
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
