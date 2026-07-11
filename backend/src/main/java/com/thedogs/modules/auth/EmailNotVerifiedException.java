package com.thedogs.modules.auth;

/**
 * Thrown on login when the credentials are correct but the account's email has not been confirmed
 * yet (AUTH-09). Only ever thrown AFTER a successful password check so it cannot be used to probe
 * verification status of accounts the caller does not control.
 */
public class EmailNotVerifiedException extends RuntimeException {

  public EmailNotVerifiedException() {
    super("Email address not verified");
  }
}
