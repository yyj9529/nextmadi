package com.phraselog.common.web;

import org.springframework.http.HttpStatus;

public class ApiErrorException extends RuntimeException {

  private final HttpStatus status;
  private final String errorCode;
  private final String userMessage;
  private final String developerHint;
  private final boolean retryable;

  public ApiErrorException(
      HttpStatus status,
      String errorCode,
      String userMessage,
      String developerHint,
      boolean retryable) {
    super(errorCode);
    this.status = status;
    this.errorCode = errorCode;
    this.userMessage = userMessage;
    this.developerHint = developerHint;
    this.retryable = retryable;
  }

  public HttpStatus status() {
    return status;
  }

  public String errorCode() {
    return errorCode;
  }

  public String userMessage() {
    return userMessage;
  }

  public String developerHint() {
    return developerHint;
  }

  public boolean retryable() {
    return retryable;
  }
}
