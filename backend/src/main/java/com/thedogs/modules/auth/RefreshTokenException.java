package com.thedogs.modules.auth;

/**
 * AUTH-03: Signals a refresh-token validation failure with a machine-readable error code.
 *
 * <p>All subtypes map to HTTP 401. The controller catches this before global exception handling so
 * it can clear the refresh cookie on theft detection before the response is committed.
 */
public class RefreshTokenException extends RuntimeException {

  private final String errorCode;

  public RefreshTokenException(String errorCode, String message) {
    super(message);
    this.errorCode = errorCode;
  }

  public String getErrorCode() {
    return errorCode;
  }

  /** The token was already used — theft detected; entire family has been revoked. */
  public static RefreshTokenException reused() {
    return new RefreshTokenException("refresh_reused", "Refresh token already used");
  }

  /** The token was revoked (e.g., previous logout invalidated the family). */
  public static RefreshTokenException revoked() {
    return new RefreshTokenException("refresh_revoked", "Refresh token has been revoked");
  }

  /** The token is past its expiry window. */
  public static RefreshTokenException expired() {
    return new RefreshTokenException("refresh_expired", "Refresh token has expired");
  }

  /** The token hash was not found in the database. */
  public static RefreshTokenException invalid() {
    return new RefreshTokenException("invalid_refresh", "Refresh token not found");
  }
}
