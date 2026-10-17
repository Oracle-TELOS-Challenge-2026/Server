package com.bintime.store.dto;

import com.bintime.common.dto.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OwnerReservationListResponse(
    @Schema(
            description = "선점 없는 승인 대기 신청. 신청 시각·ID 오름차순",
            requiredMode = Schema.RequiredMode.REQUIRED)
        List<OwnerReservationResponse> items,
    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) CursorPage page) {}
