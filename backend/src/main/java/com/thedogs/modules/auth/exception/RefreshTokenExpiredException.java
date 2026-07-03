package com.thedogs.modules.auth.exception;

/** Thrown when a refresh token exists in the database but its expiry time has passed. */
public class RefreshTokenExpiredException extends RuntimeException {

  public RefreshTokenExpiredException() {
    super("Refresh token has expired");
  }
}
