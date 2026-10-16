package com.bintime.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.bintime.search.adapter.ElasticsearchStoreAdapter;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ElasticsearchAuthenticationTests {
  @Test
  void basicCredentialsAndExplicitAuthorizationReachElasticsearch() throws Exception {
    var received = new AtomicReference<String>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/stores/_pit",
        exchange -> {
          received.set(exchange.getRequestHeaders().getFirst("Authorization"));
          byte[] body = "{\"id\":\"pit\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    try {
      String base = "http://127.0.0.1:" + server.getAddress().getPort();
      var json = JsonMapper.builder().build();
      assertThat(
              new ElasticsearchStoreAdapter(json, base, "stores", "", "elastic", "test-password")
                  .open())
          .isEqualTo("pit");
      assertThat(received.get())
          .isEqualTo(
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString("elastic:test-password".getBytes(StandardCharsets.UTF_8)));
      new ElasticsearchStoreAdapter(
              json, base, "stores", "ApiKey test-key", "elastic", "test-password")
          .open();
      assertThat(received.get()).isEqualTo("ApiKey test-key");
      new ElasticsearchStoreAdapter(json, base, "stores", "", "", "").open();
      assertThat(received.get()).isNull();
    } finally {
      server.stop(0);
    }
  }
}
