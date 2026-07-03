package com.thedogs.modules.auth.exception;

/** Thrown when registration is attempted with an email that already exists (AUTH-04, AC-2). */
public class EmailTakenException extends RuntimeException {
  public EmailTakenException() {
    super("email_taken");
  }
}
