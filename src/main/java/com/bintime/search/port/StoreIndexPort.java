package com.bintime.search.port;

import com.bintime.search.service.StoreSearchService.Query;
import com.bintime.store.contract.SearchStoreCatalog.StoreDocument;
import java.util.*;

public interface StoreIndexPort {
  String open();

  void close(String pit);

  Page search(Query query, String pit, List<Object> after, float[] vector, int size);

  void create(String index);

  void upsert(String index, StoreDocument store, float[] vector);

  void activate(String index);

  record Hit(StoreDocument store, List<Object> sort) {}

  record Page(String pit, List<Hit> hits) {}
}
