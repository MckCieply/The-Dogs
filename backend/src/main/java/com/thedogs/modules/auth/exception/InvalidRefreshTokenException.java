package com.thedogs.modules.auth.exception;

/** Thrown when the presented refresh token does not match any row in the database. */
public class InvalidRefreshTokenException extends RuntimeException {

  public InvalidRefreshTokenException() {
    super("Invalid refresh token");
  }
}
