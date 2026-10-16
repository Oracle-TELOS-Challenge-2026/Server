package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;

@Schema(description = "일반 슬롯 등록. 종료·가격은 현재 시술에서 결정하며 영업시간 전체 포함")
public record CreateSlotRequest(
    @NotBlank @Size(max = 64) String serviceId, @NotNull OffsetDateTime startAt) {}
