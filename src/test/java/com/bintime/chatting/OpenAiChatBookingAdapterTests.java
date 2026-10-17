package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;

import com.bintime.chatting.adapter.OpenAiChatBookingAdapter;
import com.bintime.common.web.ApiBusinessException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OpenAiChatBookingAdapterTests {
  @Test
  void sendsStrictStructuredInputAndSafelyRejectsFailures() throws Exception {
    var json = JsonMapper.builder().build();
    var received = new AtomicReference<String>();
    var reply = new AtomicReference<String>();
    var code = new AtomicInteger(200);
    var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    http.createContext(
        "/responses",
        exchange -> {
          received.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          var bytes = reply.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(code.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    http.start();
    try {
      var adapter =
          new OpenAiChatBookingAdapter(
              json, "test-key", "gpt-4.1-mini", "http://127.0.0.1:" + http.getAddress().getPort());
      String extracted =
          "{\"bookingRequested\":true,\"date\":\"2026-10-18\",\"time\":\"17:00\",\"serviceDescription\":\"커트\"}";
      reply.set(
          json.writeValueAsString(
              Map.of(
                  "status",
                  "completed",
                  "output",
                  List.of(
                      Map.of(
                          "content", List.of(Map.of("type", "output_text", "text", extracted)))))));
      var sent = OffsetDateTime.parse("2026-10-17T15:30:00Z");
      assertThat(adapter.analyze("내일 17시에 머리자르고 싶어요", sent).time()).isEqualTo("17:00");
      var payload = json.readTree(received.get());
      assertThat(payload.path("input").asText()).isEqualTo("내일 17시에 머리자르고 싶어요");
      assertThat(payload.path("instructions").asText()).contains("2026-10-18T00:30", "Asia/Seoul");
      assertThat(payload.at("/text/format/strict").asBoolean()).isTrue();
      assertThat(payload.at("/text/format/schema/additionalProperties").asBoolean()).isFalse();
      assertThat(payload.path("store").asBoolean()).isFalse();
      assertThat(adapter.analyze("내일 3시에 커트 예약하고 싶어요", sent).time()).isNull();
      reply.set(
          json.writeValueAsString(
              Map.of(
                  "status",
                  "completed",
                  "output",
                  List.of(
                      Map.of(
                          "content",
                          List.of(
                              Map.of(
                                  "type",
                                  "output_text",
                                  "text",
                                  extracted.replace("17:00", "03:00"))))))));
      assertThat(adapter.analyze("내일 03:00에 예약하고 싶어요", sent).time()).isEqualTo("03:00");
      for (String invalid :
          List.of(
              "{}",
              "{\"status\":\"incomplete\"}",
              "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}",
              "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}")) {
        reply.set(invalid);
        assertThatThrownBy(() -> adapter.analyze("예약", sent))
            .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      }
      code.set(429);
      reply.set("{\"private\":\"must not leak\"}");
      assertThatThrownBy(() -> adapter.analyze("예약", sent))
          .isInstanceOf(ApiBusinessException.class)
          .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      assertThatThrownBy(
              () ->
                  new OpenAiChatBookingAdapter(json, "", "gpt-4.1-mini", "invalid")
                      .analyze("예약", sent))
          .hasMessage("OPENAI_NOT_CONFIGURED");
    } finally {
      http.stop(0);
    }
  }
}
