package com.bintime.chatting.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "고객이 확인한 제안 버전. 금액·시술·시간은 서버에 저장된 제안을 사용한다.")
public record ConfirmBookingProposalRequest(
    @Schema(
            description = "화면에 표시된 bookingProposal.version",
            requiredMode = Schema.RequiredMode.REQUIRED,
            minimum = "1",
            maximum = "9007199254740991",
            example = "1")
        Long expectedVersion) {}
