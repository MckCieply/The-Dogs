package com.thedogs.modules.auth;

/** Thrown when an IP address has exceeded the login-attempt rate limit (AUTH-02). */
public class TooManyLoginAttemptsException extends RuntimeException {

  private final long retryAfterSeconds;

  public TooManyLoginAttemptsException(long retryAfterSeconds) {
    super("Too many login attempts");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long getRetryAfterSeconds() {
    return retryAfterSeconds;
  }
}
