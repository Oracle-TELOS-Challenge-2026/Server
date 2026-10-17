package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "예약 의사 분석에 사용한 고객 원문. 오래된 메시지부터 정렬")
public record ChatBookingContextMessage(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sequence,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String content,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {}
