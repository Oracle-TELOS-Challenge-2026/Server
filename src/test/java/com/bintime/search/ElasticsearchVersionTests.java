package com.bintime.search;

import static org.assertj.core.api.Assertions.*;

import com.bintime.search.adapter.ElasticsearchStoreAdapter;
import com.bintime.search.service.SearchFailure;
import com.bintime.store.contract.SearchStoreCatalog.StoreDocument;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ElasticsearchVersionTests {
  @Test
  void onlyAnExternalDocumentVersionConflictIsIgnored() throws Exception {
    var status = new AtomicInteger(409);
    var type = new AtomicReference<>("version_conflict_engine_exception");
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          byte[] body =
              ("{\"error\":{\"type\":\"" + type.get() + "\"}}").getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      var index =
          new ElasticsearchStoreAdapter(
              JsonMapper.builder().build(),
              "http://127.0.0.1:" + server.getAddress().getPort(),
              "test-stores",
              "",
              "",
              "");
      var document =
          new StoreDocument("store", "매장", "NAIL", null, null, null, 37.5, 127, "소개", 1, List.of());
      assertThatCode(() -> index.upsert("test-stores", document, null)).doesNotThrowAnyException();
      assertThatThrownBy(index::open).isInstanceOf(SearchFailure.class);
      type.set("another_conflict");
      assertThatThrownBy(() -> index.upsert("test-stores", document, null))
          .isInstanceOf(SearchFailure.class);
      status.set(503);
      type.set("unavailable_shards_exception");
      assertThatThrownBy(() -> index.upsert("test-stores", document, null))
          .isInstanceOf(SearchFailure.class);
    } finally {
      server.stop(0);
    }
  }
}
