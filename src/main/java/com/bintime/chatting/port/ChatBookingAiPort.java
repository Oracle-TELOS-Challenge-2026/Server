package com.bintime.chatting.port;

import java.time.OffsetDateTime;

public interface ChatBookingAiPort {
  Extraction analyze(String text, OffsetDateTime sentAt);

  record Extraction(
      boolean bookingRequested, String date, String time, String serviceDescription) {}
}
