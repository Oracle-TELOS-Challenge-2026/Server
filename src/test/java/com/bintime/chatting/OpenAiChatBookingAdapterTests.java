package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;

import com.bintime.chatting.adapter.OpenAiChatBookingAdapter;
import com.bintime.chatting.dto.ChatBookingContextMessage;
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
      assertThat(adapter.analyze(messages(sent, "내일 17시에 머리자르고 싶어요")).time()).isEqualTo("17:00");
      var payload = json.readTree(received.get());
      var input = json.readTree(payload.path("input").asText());
      assertThat(input.get(0).path("content").asText()).isEqualTo("내일 17시에 머리자르고 싶어요");
      assertThat(input.get(0).path("sentAt").asText()).contains("2026-10-18T00:30", "Asia/Seoul");
      assertThat(payload.path("instructions").asText()).contains("최대 5개", "Asia/Seoul");
      assertThat(payload.at("/text/format/strict").asBoolean()).isTrue();
      assertThat(payload.at("/text/format/schema/additionalProperties").asBoolean()).isFalse();
      assertThat(payload.path("store").asBoolean()).isFalse();
      assertThat(adapter.analyze(messages(sent, "내일 3시에 커트 예약하고 싶어요")).time()).isNull();
      var split = messages(sent, "예약하고 싶어요", "내일이요", "3시요", "커트로요", "아니 17시로 할게요");
      assertThat(adapter.analyze(split).time()).isEqualTo("17:00");
      var sentMessages = json.readTree(json.readTree(received.get()).path("input").asText());
      assertThat(sentMessages.size()).isEqualTo(5);
      assertThat(sentMessages.get(0).path("content").asText()).isEqualTo("예약하고 싶어요");
      assertThat(sentMessages.get(4).path("content").asText()).isEqualTo("아니 17시로 할게요");
      assertThat(adapter.analyze(messages(sent, "오후 5시 커트 예약", "아니 3시요")).time()).isNull();
      assertThat(adapter.analyze(messages(sent, "3시 커트 예약", "오후요")).time()).isEqualTo("17:00");
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
      assertThat(adapter.analyze(messages(sent, "내일 03:00에 예약하고 싶어요")).time()).isEqualTo("03:00");
      for (String invalid :
          List.of(
              "{}",
              "{\"status\":\"incomplete\"}",
              "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"{}\"}]}]}",
              "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}")) {
        reply.set(invalid);
        assertThatThrownBy(() -> adapter.analyze(messages(sent, "예약")))
            .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      }
      code.set(429);
      reply.set("{\"private\":\"must not leak\"}");
      assertThatThrownBy(() -> adapter.analyze(messages(sent, "예약")))
          .isInstanceOf(ApiBusinessException.class)
          .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      assertThatThrownBy(
              () ->
                  new OpenAiChatBookingAdapter(json, "", "gpt-4.1-mini", "invalid")
                      .analyze(messages(sent, "예약")))
          .hasMessage("OPENAI_NOT_CONFIGURED");
    } finally {
      http.stop(0);
    }
  }

  static List<ChatBookingContextMessage> messages(OffsetDateTime sent, String... texts) {
    var messages = new ArrayList<ChatBookingContextMessage>();
    for (int i = 0; i < texts.length; i++)
      messages.add(new ChatBookingContextMessage("msg-" + i, i + 1, texts[i], sent.plusSeconds(i)));
    return messages;
  }
}
