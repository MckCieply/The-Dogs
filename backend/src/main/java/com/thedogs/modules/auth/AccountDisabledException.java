package com.thedogs.modules.auth;

/** Thrown when a user account exists but is disabled (AUTH-02). */
public class AccountDisabledException extends RuntimeException {

  public AccountDisabledException() {
    super("Account is disabled");
  }
}
