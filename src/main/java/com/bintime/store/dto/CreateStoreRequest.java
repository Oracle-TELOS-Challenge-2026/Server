package com.bintime.store.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.util.List;

@Schema(description = "사장님 매장 등록. 소유자는 인증 정보로 결정하며 주간 영업시간 7개가 필수")
public record CreateStoreRequest(
    @NotBlank @Size(max = 100) String name,
    @NotBlank @Schema(allowableValues = {"NAIL", "HAIR", "EYELASH", "SKIN"}) String category,
    @NotBlank @Size(max = 300) String address,
    @NotNull StoreDetailResponse.Location location,
    @Size(max = 1000) String description,
    @Size(max = 2048) String imageUrl,
    @NotNull @Size(min = 7, max = 7) List<UpdateStoreRequest.OpeningHourItem> openingHours) {}
