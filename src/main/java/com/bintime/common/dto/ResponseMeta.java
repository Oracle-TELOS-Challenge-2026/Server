package com.bintime.common.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.ZoneId;

public record ResponseMeta(
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "Asia/Seoul 기준 응답 생성 시각",
            example = "2026-07-12T07:00:00")
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        LocalDateTime timestamp,
    @Schema(
            requiredMode = Schema.RequiredMode.REQUIRED,
            description = "쿼리 문자열을 제외한 요청 경로",
            example = "/api/v1/users/me")
        String path) {
  public static ResponseMeta now(String path) {
    return new ResponseMeta(LocalDateTime.now(ZoneId.of("Asia/Seoul")), path);
  }
}
