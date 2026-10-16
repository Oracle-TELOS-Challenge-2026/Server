package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

@Schema(description = "예약 제안 카드의 현재 상태. 낮은 version으로 최신 상태를 덮어쓰지 않는다.")
public record ChatBookingProposalResponse(
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "proposal_001") String id,
    @Schema(
            description = "제안의 원본 고객 메시지 ID",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "msg_001")
        String sourceMessageId,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "svc_gel") String serviceId,
    @Schema(
            description = "제안 시 저장한 시술·추가 옵션 설명",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "젤 네일 + 기존 젤 제거 + 아트 2개")
        String serviceDescription,
    @Schema(
            description = "예약 구간의 시작(포함)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-20T14:00:00+09:00")
        OffsetDateTime startAt,
    @Schema(
            description = "예약 구간의 종료(미포함). 시작 시각에 소요 시간을 더한 서버 계산값",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-10-20T15:30:00+09:00")
        OffsetDateTime endAt,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "15",
            maximum = "480",
            example = "90")
        Integer durationMinutes,
    @Schema(
            description = "제안 시 저장한 최종 금액(KRW 정수 원)",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "0",
            maximum = "10000000",
            example = "75000")
        Long price,
    @Schema(
            description = "고객 확인 대기 / 예약 확정 / 새 제안으로 대체 / 고객 요청 변경으로 재검토 필요",
            requiredMode = Schema.RequiredMode.REQUIRED,
            allowableValues = {"PENDING", "CONFIRMED", "SUPERSEDED", "REVIEW_REQUIRED"},
            example = "PENDING")
        String status,
    @Schema(
            description = "상태 변경 시 증가하는 버전",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991",
            example = "1")
        Long version,
    @Schema(description = "확정된 예약 ID. 미확정이면 null", nullable = true, example = "rsv_001")
        String reservationId,
    @Schema(description = "연결된 예약 대기 ID. 지정하지 않았다면 null", nullable = true, example = "wait_001")
        String waitlistId) {}
