package com.bintime.common.web;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ApiResponse;
import com.bintime.common.dto.BaseResponse;
import com.bintime.common.dto.ResponseMeta;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import tools.jackson.databind.json.JsonMapper;

@RestControllerAdvice
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {
  private final JsonMapper mapper;

  public ApiResponseAdvice(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean supports(
      MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(
      Object body,
      MethodParameter returnType,
      MediaType contentType,
      Class<? extends HttpMessageConverter<?>> converterType,
      ServerHttpRequest request,
      ServerHttpResponse response) {
    String path = request.getURI().getRawPath();
    if (!path.startsWith("/api/v1/")) {
      return body;
    }
    int status = ((ServletServerHttpResponse) response).getServletResponse().getStatus();
    // 204·리다이렉트는 본문이 없고 SSE는 JSON 봉투와 다른 전송 형식이다.
    if (status == 204
        || status == 205
        || (status >= 300 && status < 400)
        || (status < 400 && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType))) {
      return body;
    }
    ResponseMeta meta = ResponseMeta.now(path);
    BaseResponse<?> envelope;
    if (body instanceof BaseResponse<?> wrapped) {
      // 이미 감싼 응답은 중복 wrapping 없이 실제 요청의 메타만 채운다.
      envelope = wrapped.withMeta(meta);
    } else if (status >= 400) {
      HttpStatus httpStatus = HttpStatus.resolve(status);
      envelope =
          new ApiErrorResponse(
              new ApiError(
                  httpStatus == null ? "HTTP_ERROR" : httpStatus.name(),
                  httpStatus == null ? "Request failed" : httpStatus.getReasonPhrase(),
                  List.of(),
                  status == 429 || status == 503),
              meta);
    } else {
      envelope = new ApiResponse<>(body, meta);
    }
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    // String 반환형은 선택된 컨버터가 Jackson으로 다시 바뀌지 않으므로 직접 직렬화한다.
    return StringHttpMessageConverter.class.isAssignableFrom(converterType)
        ? mapper.writeValueAsString(envelope)
        : envelope;
  }
}
