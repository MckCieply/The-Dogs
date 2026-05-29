package com.thedogs.modules.auth.dto;

/** Response body for POST /api/v1/auth/login (AUTH-02). */
public record LoginResponse(String accessToken, String tokenType, long expiresIn) {

  /** Convenience factory — always sets tokenType to "Bearer" per OAuth 2.0 convention. */
  public static LoginResponse bearer(String accessToken, long expiresIn) {
    return new LoginResponse(accessToken, "Bearer", expiresIn);
  }
}
