package com.bintime.common.web;

import com.bintime.common.dto.ApiError;
import com.bintime.common.dto.ApiErrorResponse;
import com.bintime.common.dto.ResponseMeta;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception ex, Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    String path = ((ServletWebRequest) request).getRequest().getRequestURI();
    if (!path.startsWith("/api/v1/")) {
      return super.handleExceptionInternal(ex, body, headers, status, request);
    }
    return failure(status, headers, path);
  }

  @ExceptionHandler(com.bintime.search.service.SearchFailure.class)
  ResponseEntity<Object> handleSearch(
      com.bintime.search.service.SearchFailure ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            new ApiErrorResponse(
                new ApiError(ex.code, ex.getMessage(), List.of(), ex.retryable()),
                ResponseMeta.now(request.getRequestURI())));
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> handleUnexpectedException(Exception ex, HttpServletRequest request)
      throws Exception {
    if (!request.getRequestURI().startsWith("/api/v1/")) {
      throw ex;
    }
    log.error("API request failed: {}", request.getRequestURI(), ex);
    return failure(HttpStatus.INTERNAL_SERVER_ERROR, new HttpHeaders(), request.getRequestURI());
  }

  @ExceptionHandler(com.bintime.common.web.ApiBusinessException.class)
  ResponseEntity<Object> handleStoreException(
      com.bintime.common.web.ApiBusinessException ex, HttpServletRequest request) {
    return ResponseEntity.status(ex.status)
        .body(
            new ApiErrorResponse(
                new ApiError(ex.code, ex.getMessage(), List.of(), false),
                ResponseMeta.now(request.getRequestURI())));
  }

  private ResponseEntity<Object> failure(HttpStatusCode status, HttpHeaders headers, String path) {
    HttpStatus knownStatus = HttpStatus.resolve(status.value());
    ApiError error =
        new ApiError(
            knownStatus == null ? "HTTP_ERROR" : knownStatus.name(),
            knownStatus == null ? "Request failed" : knownStatus.getReasonPhrase(),
            List.of(),
            status.value() == 429 || status.value() == 503);
    return ResponseEntity.status(status)
        .headers(headers)
        .contentType(MediaType.APPLICATION_JSON)
        .body(new ApiErrorResponse(error, ResponseMeta.now(path)));
  }
}
