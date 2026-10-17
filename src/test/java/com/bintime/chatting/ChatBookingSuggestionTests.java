package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.port.ChatBookingAiPort;
import com.bintime.chatting.port.ChatBookingAiPort.Extraction;
import com.bintime.chatting.service.ChatBookingSuggestionService;
import com.bintime.chatting.service.ChatService;
import com.bintime.common.web.ApiBusinessException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ChatBookingSuggestionTests {
  ChatService chats = mock(ChatService.class);
  ChatBookingAiPort ai = mock(ChatBookingAiPort.class);
  ChatBookingSuggestionService service = new ChatBookingSuggestionService(chats, ai);
  ChatMessageResponse source =
      new ChatMessageResponse(
          "message",
          "room",
          1L,
          new ChatMessageResponse.Sender("customer", "CUSTOMER", "고객"),
          "client",
          "TEXT",
          "내일 17시에 머리자르고 싶어요",
          OffsetDateTime.parse("2026-10-17T23:30:00+09:00"));

  @BeforeEach
  void source() {
    when(chats.bookingSource(anyString(), eq("room"), eq("message"))).thenReturn(source);
  }

  @Test
  void extractsTheOriginalMessageAndSharesItsResultBetweenParticipants() {
    when(ai.analyze(source.content(), source.createdAt()))
        .thenReturn(new Extraction(true, "2026-10-18", "17:00", "커트"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.sourceMessageId()).isEqualTo("message");
    assertThat(result.sourceContent()).isEqualTo(source.content());
    assertThat(result.bookingRequested()).isTrue();
    assertThat(result.date()).isEqualTo("2026-10-18");
    assertThat(result.time()).isEqualTo("17:00");
    assertThat(result.serviceDescription()).isEqualTo("커트");
    assertThat(service.analyze("customer", "room", "message")).isEqualTo(result);
    verify(ai).analyze(source.content(), source.createdAt());
    verify(chats, times(2)).bookingSource("owner", "room", "message");
    verify(chats, times(2)).bookingSource("customer", "room", "message");
  }

  @Test
  void nonBookingMessagesCannotSupplyProposalFieldsEvenIfTheModelReturnsThem() {
    when(ai.analyze(anyString(), any()))
        .thenReturn(new Extraction(false, "2026-10-18", "17:00", "탈색"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.bookingRequested()).isFalse();
    assertThat(result.date()).isNull();
    assertThat(result.time()).isNull();
    assertThat(result.serviceDescription()).isNull();
  }

  @Test
  void ambiguousDetailsRemainNullWithoutBlockingARealBookingRequest() {
    when(ai.analyze(anyString(), any())).thenReturn(new Extraction(true, null, null, "커트"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.bookingRequested()).isTrue();
    assertThat(result.date()).isNull();
    assertThat(result.time()).isNull();
  }

  @ParameterizedTest
  @CsvSource({"2026-02-30,17:00", "2026-2-03,17:00", "2026-10-18,24:00", "2026-10-18,3:00"})
  void invalidModelValuesAreNotAcceptedOrCached(String date, String time) {
    when(ai.analyze(anyString(), any())).thenReturn(new Extraction(true, date, time, null));
    assertThatThrownBy(() -> service.analyze("owner", "room", "message"))
        .isInstanceOf(ApiBusinessException.class)
        .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
    when(ai.analyze(anyString(), any()))
        .thenReturn(new Extraction(true, "2026-10-18", "17:00", null));
    assertThat(service.analyze("owner", "room", "message").time()).isEqualTo("17:00");
    verify(ai, times(2)).analyze(anyString(), any());
  }

  @Test
  void sourceAndMembershipAreRecheckedAfterAnalysisAndBeforeEveryCachedRead() {
    when(ai.analyze(anyString(), any())).thenReturn(new Extraction(true, null, null, null));
    service.analyze("owner", "room", "message");
    when(chats.bookingSource("owner", "room", "message"))
        .thenThrow(new ApiBusinessException(404, "ROOM_NOT_FOUND"));
    assertThatThrownBy(() -> service.analyze("owner", "room", "message"))
        .hasMessage("ROOM_NOT_FOUND");
    verify(ai).analyze(anyString(), any());
    when(chats.bookingSource("customer", "room", "message"))
        .thenReturn(source)
        .thenThrow(new ApiBusinessException(409, "PROPOSAL_SOURCE_OUTDATED"));
    assertThatThrownBy(() -> service.analyze("customer", "room", "message"))
        .hasMessage("PROPOSAL_SOURCE_OUTDATED");
  }
}
