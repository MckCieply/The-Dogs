package com.thedogs.modules.auth.exception;

/** Thrown when a refresh token that has already been used is presented (theft detection). */
public class RefreshTokenReusedException extends RuntimeException {

  public RefreshTokenReusedException() {
    super("Refresh token has already been used");
  }
}
