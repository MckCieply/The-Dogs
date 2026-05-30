package com.thedogs.modules.auth.exception;

/** Thrown when a refresh token has been explicitly revoked (e.g. via logout or theft detection). */
public class RefreshTokenRevokedException extends RuntimeException {

  public RefreshTokenRevokedException() {
    super("Refresh token has been revoked");
  }
}
