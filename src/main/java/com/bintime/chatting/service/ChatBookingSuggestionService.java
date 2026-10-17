package com.bintime.chatting.service;

import com.bintime.chatting.dto.ChatBookingSuggestionResponse;
import com.bintime.chatting.port.ChatBookingAiPort;
import com.bintime.chatting.service.ChatService.BookingContext;
import com.bintime.common.web.ApiBusinessException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!api-docs")
public class ChatBookingSuggestionService {
  private final ChatService chats;
  private final ChatBookingAiPort ai;
  private final Map<String, ChatBookingSuggestionResponse> cache = new LinkedHashMap<>();

  public ChatBookingSuggestionService(ChatService chats, ChatBookingAiPort ai) {
    this.chats = chats;
    this.ai = ai;
  }

  public ChatBookingSuggestionResponse analyze(String user, String room, String messageId) {
    var context = chats.bookingContext(user, room, messageId);
    var source = context.source();
    ChatBookingSuggestionResponse response;
    synchronized (cache) {
      response = cache.get(source.id());
    }
    if (response != null && !response.contextMessages().equals(context.messages())) response = null;
    if (response == null) {
      // shortcut: 동시 최초 조회는 중복 분석 가능. 호출량 증가 시 메시지별 요청을 병합한다.
      // 메시지 저장 트랜잭션·방 잠금 밖에서 호출한다. AI 장애로 채팅 저장을 막지 않는다.
      response = extract(context);
    }
    // 분석 중 새 메시지·예약 확정·소유자 변경이 생기면 오래된 대화로 제안하지 않는다.
    ApiBusinessException.require(
        context.equals(chats.bookingContext(user, room, messageId)),
        409,
        "PROPOSAL_SOURCE_OUTDATED");
    synchronized (cache) {
      cache.put(source.id(), response);
      if (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
    }
    return response;
  }

  private ChatBookingSuggestionResponse extract(BookingContext context) {
    var source = context.source();
    var result =
        context.messages().isEmpty()
            ? new ChatBookingAiPort.Extraction(false, null, null, null)
            : ai.analyze(context.messages());
    if (result == null) throw new ApiBusinessException(503, "CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
    String date = null, time = null, service = null;
    if (result.bookingRequested()) {
      try {
        if (result.date() != null) {
          if (!result.date().matches("\\d{4}-\\d{2}-\\d{2}")) throw new DateTimeException("date");
          var parsed = LocalDate.parse(result.date());
          if (parsed.getYear() < 1) throw new DateTimeException("date");
          date = parsed.toString();
        }
        if (result.time() != null) {
          if (!result.time().matches("([01]\\d|2[0-3]):[0-5]\\d"))
            throw new DateTimeException("time");
          time = LocalTime.parse(result.time()).format(DateTimeFormatter.ofPattern("HH:mm"));
        }
      } catch (DateTimeException failure) {
        throw new ApiBusinessException(503, "CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      }
      if (result.serviceDescription() != null && !result.serviceDescription().isBlank()) {
        service = result.serviceDescription().strip();
        if (service.length() > 1000)
          throw new ApiBusinessException(503, "CHAT_BOOKING_ANALYSIS_UNAVAILABLE");
      }
    }
    return new ChatBookingSuggestionResponse(
        source.id(),
        source.content(),
        result.bookingRequested(),
        date,
        time,
        service,
        context.messages());
  }
}
