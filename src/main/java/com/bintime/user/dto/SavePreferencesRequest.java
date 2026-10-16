package com.bintime.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import tools.jackson.databind.annotation.JsonDeserialize;

@Schema(description = "SavePreferencesRequest")
public record SavePreferencesRequest(
    @Schema(description = "categories", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        List<@NotNull @Pattern(regexp = "NAIL|HAIR|EYELASH|SKIN") String> categories,
    @Schema(description = "keywords", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        List<@NotBlank String> keywords,
    @Schema(
            description = "최대 금액, 0~10000000원. null은 상한 없음",
            requiredMode = Schema.RequiredMode.REQUIRED,
            nullable = true,
            example = "60000")
        @JsonProperty(required = true)
        @PositiveOrZero
        @Max(10000000)
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long maxPrice,
    @Schema(
            description = "자유 입력 선호도. 생략하면 기존 값 유지, 빈 문자열이면 초기화. null은 허용하지 않음",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            type = "string",
            maxLength = 2000,
            example = "차분한 분위기에서 심플한 네일을 받고 싶어요.")
        @Size(max = 2000)
        @JsonDeserialize(using = StrictStringDeserializer.class)
        String text,
    @Schema(
            description = "GET /users/me의 최상위 version. 이름·선호도가 공유하며 불일치 시 409 VERSION_CONFLICT",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        @NotNull
        @Positive
        @JsonDeserialize(using = StrictLongDeserializer.class)
        Long expectedVersion) {}
