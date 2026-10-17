package com.bintime.chatting.port;

import com.bintime.chatting.dto.ChatBookingContextMessage;
import java.util.List;

public interface ChatBookingAiPort {
  Extraction analyze(List<ChatBookingContextMessage> messages);

  record Extraction(
      boolean bookingRequested, String date, String time, String serviceDescription) {}
}
