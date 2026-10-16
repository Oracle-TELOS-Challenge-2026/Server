package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JSON 실패 응답. success는 null이며 error에 오류 정보 포함")
public final class ApiErrorResponse extends BaseResponse<Void> {
  public ApiErrorResponse(ApiError error, ResponseMeta meta) {
    super(ResultType.FAILURE, null, error, meta);
  }

  @Override
  public ApiErrorResponse withMeta(ResponseMeta meta) {
    return new ApiErrorResponse(getError(), meta);
  }
}
