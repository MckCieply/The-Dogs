package com.thedogs.modules.auth.exception;

import lombok.Getter;

/**
 * Thrown when a password fails the server-side zxcvbn strength gate (AUTH-04, AC-3). Carries the
 * measured score (0–4) so the error response can include a {@code password_score} extension field
 * without the frontend re-running zxcvbn.
 */
@Getter
public class PasswordTooWeakException extends RuntimeException {

  private final int score;

  public PasswordTooWeakException(int score) {
    super("password_too_weak");
    this.score = score;
  }
}
