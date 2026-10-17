package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "저장된 고객 메시지의 예약 의사 분석. 예약이나 제안을 생성하지 않는다.")
public record ChatBookingSuggestionResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceMessageId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sourceContent,
    @Schema(
            description = "새 예약을 원하는 메시지인지. false이면 예약 제안 버튼을 표시하지 않는다.",
            requiredMode = Schema.RequiredMode.REQUIRED)
        boolean bookingRequested,
    @Schema(
            description = "메시지를 보낸 시각을 기준으로 해석한 한국 날짜. 불명확하면 null",
            nullable = true,
            example = "2026-10-18")
        String date,
    @Schema(
            description = "한국 시각 HH:mm. 17시는 17:00, 오전/오후가 불분명한 3시는 null",
            nullable = true,
            example = "17:00")
        String time,
    @Schema(description = "고객이 요청한 시술·추가 옵션. 미언급이면 null. 사장님 확인 필요", nullable = true)
        String serviceDescription) {}
