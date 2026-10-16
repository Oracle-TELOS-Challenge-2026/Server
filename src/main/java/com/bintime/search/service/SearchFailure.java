package com.bintime.search.service;

import org.springframework.http.HttpStatus;

public class SearchFailure extends RuntimeException {
  public final HttpStatus status;
  public final String code;

  public SearchFailure(HttpStatus status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public static SearchFailure bad(String code) {
    return new SearchFailure(HttpStatus.BAD_REQUEST, code);
  }

  public static SearchFailure conflict(String code) {
    return new SearchFailure(HttpStatus.CONFLICT, code);
  }

  public static SearchFailure unavailable() {
    return new SearchFailure(HttpStatus.SERVICE_UNAVAILABLE, "SEARCH_DEPENDENCY_UNAVAILABLE");
  }
}
