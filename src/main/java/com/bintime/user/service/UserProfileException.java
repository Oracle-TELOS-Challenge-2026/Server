package com.bintime.user.service;

import org.springframework.http.HttpStatus;

public class UserProfileException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public UserProfileException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
