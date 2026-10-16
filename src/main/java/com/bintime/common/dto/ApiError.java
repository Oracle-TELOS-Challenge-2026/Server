package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "실패 응답의 오류 정보")
public record ApiError(String code, String message, List<Detail> details, Boolean retryable) {
  @Schema(name = "ApiErrorDetail")
  public record Detail(String field, String reason) {}
}
