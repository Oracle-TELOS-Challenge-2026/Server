package com.bintime.common.web;

public class ApiBusinessException extends RuntimeException {
  public final int status;
  public final String code;

  public ApiBusinessException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }

  public static void require(boolean condition, int status, String code) {
    if (!condition) throw new ApiBusinessException(status, code);
  }
}
