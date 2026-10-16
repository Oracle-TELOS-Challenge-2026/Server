package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "매장 사장님이 확인한 예약 제안. 등록 시술의 기본값과 별도로 저장한다.")
public record CreateBookingProposalRequest(
    @Schema(
            description = "이 방의 가장 최근 고객 TEXT 메시지 ID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 64,
            example = "msg_001")
        String sourceMessageId,
    @Schema(
            description = "해당 매장에 등록된 활성 시술 ID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            maxLength = 64,
            example = "svc_gel")
        String serviceId,
    @Schema(
            description = "사장님이 확인한 시술·추가 옵션 설명. 공백만 입력 불가",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minLength = 1,
            maxLength = 1000,
            example = "젤 네일 + 기존 젤 제거 + 아트 2개")
        String serviceDescription,
    @Schema(
            description = "오프셋을 포함한 시작 시각. 제안 전송만으로 시간을 선점하지 않는다",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime startAt,
    @Schema(
            description = "추가 옵션을 포함한 최종 소요 시간(분). 5분 단위",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "15",
            maximum = "480",
            multipleOf = 5,
            example = "90")
        Integer durationMinutes,
    @Schema(
            description = "추가 옵션을 포함한 최종 금액(KRW 정수 원)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "0",
            maximum = "10000000",
            example = "75000")
        Long price,
    @Schema(
            description = "선택한 예약 대기 ID. 확정 시 기존 대기 충족 규칙으로 처리한다",
            nullable = true,
            maxLength = 64,
            example = "wait_001")
        String waitlistId) {}
