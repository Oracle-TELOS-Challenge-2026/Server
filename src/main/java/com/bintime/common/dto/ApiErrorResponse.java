package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record ApiErrorResponse(Error error, ResponseMeta meta) {
  @Schema(name = "ApiError")
  public record Error(String code, String message, List<Detail> details, Boolean retryable) {}

  @Schema(name = "ApiErrorDetail")
  public record Detail(String field, String reason) {}
}
