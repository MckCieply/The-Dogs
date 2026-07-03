package com.thedogs.modules.auth.exception;

/**
 * Thrown when a password-reset token is unknown, already used, or expired (AUTH-05, AC-8/AC-9).
 * Deliberately one exception for all three states — clients must not be able to distinguish them.
 */
public class InvalidResetTokenException extends RuntimeException {
  public InvalidResetTokenException() {
    super("invalid_reset_token");
  }
}
