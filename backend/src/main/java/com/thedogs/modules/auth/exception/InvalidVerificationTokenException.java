package com.thedogs.modules.auth.exception;

/**
 * Thrown when an email-verification token is unknown, already used, or expired (AUTH-09). One
 * exception for all three cases — clients must not be able to distinguish them, mirroring {@link
 * InvalidResetTokenException}.
 */
public class InvalidVerificationTokenException extends RuntimeException {

  public InvalidVerificationTokenException() {
    super("Invalid verification token");
  }
}
