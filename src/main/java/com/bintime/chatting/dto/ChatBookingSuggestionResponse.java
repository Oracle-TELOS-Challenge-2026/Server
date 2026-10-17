package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "최근 고객 메시지 최대 5개의 예약 의사 분석. 예약이나 제안을 생성하지 않는다.")
public record ChatBookingSuggestionResponse(
    @Schema(
            description = "최신 고객 TEXT ID. C-06 제안 생성에도 이 ID 사용",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String sourceMessageId,
    @Schema(
            description = "최신 고객 TEXT 원문. 분석 원문 전체는 contextMessages 참조",
            requiredMode = Schema.RequiredMode.REQUIRED)
        String sourceContent,
    @Schema(
            description = "대화에 아직 유효한 새 예약 요청이 있는지. false이면 예약 제안 버튼을 표시하지 않는다.",
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
        String serviceDescription,
    @Schema(
            description = "분석에 사용한 최근 고객 TEXT 최대 5개, 시간순. 마지막 확정 제안 이전은 제외. 없으면 빈 목록",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<ChatBookingContextMessage> contextMessages) {}
