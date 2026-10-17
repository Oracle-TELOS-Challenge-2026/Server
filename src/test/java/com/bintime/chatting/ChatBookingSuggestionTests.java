package com.bintime.chatting;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.bintime.chatting.dto.ChatBookingContextMessage;
import com.bintime.chatting.dto.ChatMessageResponse;
import com.bintime.chatting.port.ChatBookingAiPort;
import com.bintime.chatting.port.ChatBookingAiPort.Extraction;
import com.bintime.chatting.service.ChatBookingSuggestionService;
import com.bintime.chatting.service.ChatService;
import com.bintime.chatting.service.ChatService.BookingContext;
import com.bintime.common.web.ApiBusinessException;
import java.time.OffsetDateTime;
import java.util.List;
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
    when(chats.bookingContext(anyString(), eq("room"), eq("message"))).thenReturn(context());
  }

  BookingContext context() {
    return new BookingContext(
        source,
        List.of(
            new ChatBookingContextMessage(
                source.id(), source.sequence(), source.content(), source.createdAt())));
  }

  @Test
  void extractsTheOriginalMessageAndSharesItsResultBetweenParticipants() {
    when(ai.analyze(context().messages()))
        .thenReturn(new Extraction(true, "2026-10-18", "17:00", "커트"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.sourceMessageId()).isEqualTo("message");
    assertThat(result.sourceContent()).isEqualTo(source.content());
    assertThat(result.contextMessages()).isEqualTo(context().messages());
    assertThat(result.bookingRequested()).isTrue();
    assertThat(result.date()).isEqualTo("2026-10-18");
    assertThat(result.time()).isEqualTo("17:00");
    assertThat(result.serviceDescription()).isEqualTo("커트");
    assertThat(service.analyze("customer", "room", "message")).isEqualTo(result);
    verify(ai).analyze(context().messages());
    verify(chats, times(2)).bookingContext("owner", "room", "message");
    verify(chats, times(2)).bookingContext("customer", "room", "message");
  }

  @Test
  void nonBookingMessagesCannotSupplyProposalFieldsEvenIfTheModelReturnsThem() {
    when(ai.analyze(anyList())).thenReturn(new Extraction(false, "2026-10-18", "17:00", "탈색"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.bookingRequested()).isFalse();
    assertThat(result.date()).isNull();
    assertThat(result.time()).isNull();
    assertThat(result.serviceDescription()).isNull();
  }

  @Test
  void ambiguousDetailsRemainNullWithoutBlockingARealBookingRequest() {
    when(ai.analyze(anyList())).thenReturn(new Extraction(true, null, null, "커트"));
    var result = service.analyze("owner", "room", "message");
    assertThat(result.bookingRequested()).isTrue();
    assertThat(result.date()).isNull();
    assertThat(result.time()).isNull();
  }

  @Test
  void sendsTheWholeStoredContextAndDiscardsCachedIntentAfterBookingConfirmation() {
    var messages =
        List.of(
            new ChatBookingContextMessage(
                "old", 1L, "커트 예약하고 싶어요", source.createdAt().minusMinutes(1)),
            new ChatBookingContextMessage("message", 2L, "내일 17시요", source.createdAt()));
    when(chats.bookingContext(anyString(), eq("room"), eq("message")))
        .thenReturn(new BookingContext(source, messages));
    when(ai.analyze(messages)).thenReturn(new Extraction(true, "2026-10-18", "17:00", "커트"));
    assertThat(service.analyze("owner", "room", "message").contextMessages()).isEqualTo(messages);
    when(chats.bookingContext(anyString(), eq("room"), eq("message")))
        .thenReturn(new BookingContext(source, List.of()));
    var confirmed = service.analyze("owner", "room", "message");
    assertThat(confirmed.bookingRequested()).isFalse();
    assertThat(confirmed.contextMessages()).isEmpty();
    verify(ai).analyze(messages);
    verifyNoMoreInteractions(ai);
  }

  @Test
  void confirmationDuringAnalysisCannotReturnAnOutdatedPositiveResult() {
    when(chats.bookingContext("owner", "room", "message"))
        .thenReturn(context())
        .thenReturn(new BookingContext(source, List.of()));
    when(ai.analyze(anyList())).thenReturn(new Extraction(true, null, null, "커트"));
    assertThatThrownBy(() -> service.analyze("owner", "room", "message"))
        .hasMessage("PROPOSAL_SOURCE_OUTDATED");
  }

  @ParameterizedTest
  @CsvSource({"2026-02-30,17:00", "2026-2-03,17:00", "2026-10-18,24:00", "2026-10-18,3:00"})
  void invalidModelValuesAreNotAcceptedOrCached(String date, String time) {
    when(ai.analyze(anyList())).thenReturn(new Extraction(true, date, time, null));
    assertThatThrownBy(() -> service.analyze("owner", "room", "message"))
        .isInstanceOf(ApiBusinessException.class)
        .hasMessage("CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
    when(ai.analyze(anyList())).thenReturn(new Extraction(true, "2026-10-18", "17:00", null));
    assertThat(service.analyze("owner", "room", "message").time()).isEqualTo("17:00");
    verify(ai, times(2)).analyze(anyList());
  }

  @Test
  void sourceAndMembershipAreRecheckedAfterAnalysisAndBeforeEveryCachedRead() {
    when(ai.analyze(anyList())).thenReturn(new Extraction(true, null, null, null));
    service.analyze("owner", "room", "message");
    when(chats.bookingContext("owner", "room", "message"))
        .thenThrow(new ApiBusinessException(404, "ROOM_NOT_FOUND"));
    assertThatThrownBy(() -> service.analyze("owner", "room", "message"))
        .hasMessage("ROOM_NOT_FOUND");
    verify(ai).analyze(anyList());
    when(chats.bookingContext("customer", "room", "message"))
        .thenReturn(context())
        .thenThrow(new ApiBusinessException(409, "PROPOSAL_SOURCE_OUTDATED"));
    assertThatThrownBy(() -> service.analyze("customer", "room", "message"))
        .hasMessage("PROPOSAL_SOURCE_OUTDATED");
  }
}
