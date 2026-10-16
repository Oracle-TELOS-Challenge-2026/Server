package com.bintime.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonPropertyOrder({"resultType", "success", "error", "meta"})
@Schema(description = "모든 JSON API 응답의 공통 부모. 성공과 실패 필드는 null이어도 항상 표시")
public abstract class BaseResponse<T> {
  private final ResultType resultType;
  private final Success<T> success;
  private final ApiError error;
  private final ResponseMeta meta;

  protected BaseResponse(
      ResultType resultType, Success<T> success, ApiError error, ResponseMeta meta) {
    this.resultType = resultType;
    this.success = success;
    this.error = error;
    this.meta = meta;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  public ResultType getResultType() {
    return resultType;
  }

  @Schema(
      requiredMode = Schema.RequiredMode.REQUIRED,
      types = {"object", "null"})
  public Success<T> getSuccess() {
    return success;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true)
  public ApiError getError() {
    return error;
  }

  @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
  public ResponseMeta getMeta() {
    return meta;
  }

  public abstract BaseResponse<T> withMeta(ResponseMeta meta);

  @Schema(description = "성공 데이터 봉투")
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Success<T>(
      @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) T data) {}
}
