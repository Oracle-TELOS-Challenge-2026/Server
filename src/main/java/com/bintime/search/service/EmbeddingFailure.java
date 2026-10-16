package com.bintime.search.service;

import org.springframework.http.HttpStatus;

/** 응답 본문/키/입력 대신 안전한 오류 코드와 재시도 여부만 전달한다. */
public class EmbeddingFailure extends SearchFailure {
  private final boolean retry;

  public EmbeddingFailure(String code, boolean retry) {
    super(HttpStatus.SERVICE_UNAVAILABLE, code);
    this.retry = retry;
  }

  @Override
  public boolean retryable() {
    return retry;
  }

  public static EmbeddingFailure invalidResponse() {
    return new EmbeddingFailure("OPENAI_INVALID_RESPONSE", false);
  }
}
