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
          .hasMessage("OPENAI_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void embeddingsSendWholeJsonAndValidateResponseAndRetryPolicy() throws Exception {
    var json = JsonMapper.builder().build();
    var received = new AtomicReference<String>();
    var response = new AtomicReference<String>();
    var status = new AtomicInteger(200);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/embeddings",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer test-only");
          byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
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
      float[] vector = new float[1536];
      vector[0] = 1;
      response.set(
          json.writeValueAsString(
              Map.of(
                  "model",
                  "text-embedding-3-small",
                  "data",
                  List.of(Map.of("index", 0, "embedding", vector)))));
      String input = "{\"사용자 자유 선호\":\"네일에 관심이 있고 경력 3년 이상을 원해요\",\"more\":[true,3]}";
      assertThat(adapter.embed(input)).containsExactly(vector);
      var body = json.readTree(received.get());
      assertThat(body.path("input").asText()).isEqualTo(input);
      assertThat(body.path("encoding_format").asText()).isEqualTo("float");
      assertThat(body.path("dimensions").asInt()).isEqualTo(1536);
      response.set(
          json.writeValueAsString(
              Map.of(
                  "model",
                  "text-embedding-3-small",
                  "data",
                  List.of(Map.of("index", 0, "embedding", new float[1536])))));
      assertThatThrownBy(() -> adapter.embed(input)).hasMessage("OPENAI_INVALID_RESPONSE");
      response.set(
          "{\"model\":\"text-embedding-3-small\",\"data\":[{\"index\":0,\"embedding\":[1]}]}");
      assertThatThrownBy(() -> adapter.embed(input)).hasMessage("OPENAI_INVALID_RESPONSE");
      status.set(401);
      response.set("{}");
      assertThatThrownBy(() -> adapter.embed(input))
          .isInstanceOfSatisfying(
              com.bintime.search.service.EmbeddingFailure.class,
              e -> {
                assertThat(e.retryable()).isFalse();
                assertThat(e.code).isEqualTo("OPENAI_AUTHENTICATION_FAILED");
              });
      status.set(429);
      assertThatThrownBy(() -> adapter.embed(input))
          .isInstanceOfSatisfying(
              com.bintime.search.service.EmbeddingFailure.class,
              e -> assertThat(e.retryable()).isTrue());
      response.set("{\"error\":{\"code\":\"insufficient_quota\"}}");
      assertThatThrownBy(() -> adapter.embed(input))
          .isInstanceOfSatisfying(
              com.bintime.search.service.EmbeddingFailure.class,
              e -> {
                assertThat(e.retryable()).isFalse();
                assertThat(e.code).isEqualTo("OPENAI_QUOTA_EXHAUSTED");
              });
      status.set(503);
      assertThatThrownBy(() -> adapter.embed(input)).hasMessage("OPENAI_UNAVAILABLE");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void missingApiKeyIsPermanentFailure() {
    var adapter =
        new OpenAiIntentAdapter(
            JsonMapper.builder().build(),
            "",
            "gpt-4.1-mini",
            "text-embedding-3-small",
            "https://api.openai.com/v1");
    assertThatThrownBy(() -> adapter.embed("{}"))
        .isInstanceOfSatisfying(
            com.bintime.search.service.EmbeddingFailure.class,
            e -> {
              assertThat(e.code).isEqualTo("OPENAI_NOT_CONFIGURED");
              assertThat(e.retryable()).isFalse();
            });
  }

  @Test
  void requestTimeoutIsRetryableAndDoesNotExposeUpstreamDetails() throws Exception {
    var release = new java.util.concurrent.CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/embeddings",
        exchange -> {
          try {
            release.await(15, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var adapter =
          new OpenAiIntentAdapter(
              JsonMapper.builder().build(),
              "test-only",
              "gpt-4.1-mini",
              "text-embedding-3-small",
              "http://127.0.0.1:" + server.getAddress().getPort());
      assertThatThrownBy(() -> adapter.embed("{\"text\":\"synthetic preference\"}"))
          .isInstanceOfSatisfying(
              com.bintime.search.service.EmbeddingFailure.class,
              e -> {
                assertThat(e.retryable()).isTrue();
                assertThat(e.code).isEqualTo("OPENAI_CONNECTION_FAILED");
                assertThat(e.getMessage()).doesNotContain("test-only", "synthetic preference");
              });
    } finally {
      release.countDown();
      server.stop(0);
    }
  }
}
