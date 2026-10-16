package com.bintime.search;

import static org.assertj.core.api.Assertions.*;

import com.bintime.search.adapter.OpenAiIntentAdapter;
import com.bintime.search.dto.*;
import com.bintime.search.service.SearchFailure;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiIntentAdapterTests {
  @Test
  void sendsStrictSchemaAndDistinguishesMalformedResponsesFromUnsupported() throws Exception {
    var json = JsonMapper.builder().build();
    var received = new AtomicReference<String>();
    var response = new AtomicReference<String>();
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/responses",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      var adapter =
          new OpenAiIntentAdapter(
              json,
              "test-only",
              "gpt-4.1-mini",
              "text-embedding-3-small",
              "http://127.0.0.1:" + server.getAddress().getPort());
      var c =
          new PartialIntentConditions(null, null, null, null, null, null, null, null, List.of());
      String extracted =
          json.writeValueAsString(Map.of("supported", false, "conditions", c, "serviceText", ""));
      response.set(
          json.writeValueAsString(
              Map.of(
                  "status",
                  "completed",
                  "output",
                  List.of(
                      Map.of(
                          "content", List.of(Map.of("type", "output_text", "text", extracted)))))));
      var result =
          adapter.analyze(
              new AnalyzeIntentRequest("안녕", null),
              OffsetDateTime.parse("2026-10-16T14:00:00+09:00"));
      assertThat(result.supported()).isFalse();
      assertThat(json.readTree(received.get()).at("/text/format/strict").asBoolean()).isTrue();
      assertThat(
              json.readTree(received.get())
                  .at("/text/format/schema/additionalProperties")
                  .asBoolean())
          .isFalse();
      response.set(
          "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}");
      assertThatThrownBy(
              () -> adapter.analyze(new AnalyzeIntentRequest("네일", null), OffsetDateTime.now()))
          .isInstanceOf(SearchFailure.class);
      status.set(503);
      response.set("{}");
      assertThatThrownBy(
              () -> adapter.analyze(new AnalyzeIntentRequest("네일", null), OffsetDateTime.now()))
          .hasMessage("SEARCH_DEPENDENCY_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }
}
