package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;

import com.bintime.chatting.adapter.OpenAiChatBookingAdapter;
import com.bintime.chatting.dto.ChatBookingContextMessage;
import java.nio.file.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;

/** 명시적으로 실행할 때만 실제 키로 합성 채팅 문장을 전송한다. */
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_INTEGRATION", matches = "1")
class ChatBookingAiLiveTests {
  @Test
  void realModelSeparatesGreetingsAndCancellationAndReadsKoreanBookingDetails() throws Exception {
    String key = System.getenv("OPENAI_API_KEY");
    if (key == null || key.isBlank()) {
      key =
          Files.readAllLines(Path.of(".env")).stream()
              .filter(line -> line.startsWith("OPENAI_API_KEY="))
              .map(line -> line.substring("OPENAI_API_KEY=".length()).trim())
              .findFirst()
              .orElse("");
      if (key.length() > 1
          && (key.startsWith("\"") && key.endsWith("\"")
              || key.startsWith("'") && key.endsWith("'")))
        key = key.substring(1, key.length() - 1);
    }
    assertThat(!key.isBlank()).as("OPENAI_API_KEY configured; never print its value").isTrue();
    var adapter =
        new OpenAiChatBookingAdapter(
            JsonMapper.builder().build(), key, "gpt-4.1-mini", "https://api.openai.com/v1");
    var sent = OffsetDateTime.parse("2026-10-17T14:00:00+09:00");
    var booking = adapter.analyze(messages(sent, "내일 17시에 머리자르고 싶어요"));
    assertThat(booking.bookingRequested()).isTrue();
    assertThat(booking.date()).isEqualTo("2026-10-18");
    assertThat(booking.time()).isEqualTo("17:00");
    assertThat(booking.serviceDescription()).isNotBlank();
    assertThat(adapter.analyze(messages(sent, "ㅎㅇ")).bookingRequested()).isFalse();
    assertThat(adapter.analyze(messages(sent, "내일 예약 취소할게요")).bookingRequested()).isFalse();
    var ambiguous = adapter.analyze(messages(sent, "내일 3시에 커트 예약하고 싶어요"));
    assertThat(ambiguous.bookingRequested()).isTrue();
    assertThat(ambiguous.date()).isEqualTo("2026-10-18");
    assertThat(ambiguous.time()).isNull();
    var corrected = adapter.analyze(messages(sent, "오늘 말고 내일 오후 5시에 커트 예약하고 싶어요"));
    assertThat(corrected.bookingRequested()).isTrue();
    assertThat(corrected.date()).isEqualTo("2026-10-18");
    assertThat(corrected.time()).isEqualTo("17:00");
    var missing = adapter.analyze(messages(sent, "커트 예약하고 싶어요"));
    assertThat(missing.bookingRequested()).isTrue();
    assertThat(missing.date()).isNull();
    assertThat(missing.time()).isNull();
    var split = messages(sent, "예약하고 싶어요", "내일이요", "17시에요", "머리 자르려고요", "옵션 없이요");
    var combined = adapter.analyze(split);
    assertThat(combined.bookingRequested()).isTrue();
    assertThat(combined.date()).isEqualTo("2026-10-18");
    assertThat(combined.time()).isEqualTo("17:00");
    assertThat(combined.serviceDescription()).isNotBlank();
    var cancelled = adapter.analyze(messages(sent, "내일 17시 커트 예약하고 싶어요", "아니 예약 안 할게요"));
    assertThat(cancelled.bookingRequested()).isFalse();
    var revised = adapter.analyze(messages(sent, "내일 3시 커트 예약하고 싶어요", "아니 17시요", "감사합니다"));
    assertThat(revised.bookingRequested()).isTrue();
    assertThat(revised.time()).isEqualTo("17:00");
    var midnight =
        adapter.analyze(
            List.of(
                new ChatBookingContextMessage(
                    "before", 1, "내일 예약하고 싶어요", OffsetDateTime.parse("2026-10-17T23:59:00+09:00")),
                new ChatBookingContextMessage(
                    "after", 2, "17시에 커트로요", OffsetDateTime.parse("2026-10-18T00:01:00+09:00"))));
    assertThat(midnight.bookingRequested()).isTrue();
    assertThat(midnight.date()).isEqualTo("2026-10-18");
    assertThat(midnight.time()).isEqualTo("17:00");
  }

  static List<ChatBookingContextMessage> messages(OffsetDateTime sent, String... texts) {
    var messages = new ArrayList<ChatBookingContextMessage>();
    for (int i = 0; i < texts.length; i++)
      messages.add(new ChatBookingContextMessage("msg-" + i, i + 1, texts[i], sent.plusSeconds(i)));
    return messages;
  }
}
