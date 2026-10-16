package com.bintime.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JSON 성공 응답. 실제 데이터는 success.data에 포함")
public final class ApiResponse<T> extends BaseResponse<T> {
  public ApiResponse(T data, ResponseMeta meta) {
    super(ResultType.SUCCESS, new Success<>(data), null, meta);
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(data, null);
  }

  @Override
  public ApiResponse<T> withMeta(ResponseMeta meta) {
    return new ApiResponse<>(getSuccess().data(), meta);
  }
}
