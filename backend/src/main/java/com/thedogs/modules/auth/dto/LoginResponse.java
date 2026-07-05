package com.thedogs.modules.auth.dto;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import java.util.List;

/**
 * Response body for POST /api/v1/auth/login and /auth/register.
 *
 * <p>Carries the authenticated identity (email, displayName, roles) alongside the token: the access
 * token is held in memory only on the client (ADR-0003), so without these fields the frontend has
 * no way to know who is signed in.
 */
public record LoginResponse(
    String accessToken,
    String tokenType,
    long expiresIn,
    String email,
    String displayName,
    List<String> roles) {

  /** Convenience factory — always sets tokenType to "Bearer" per OAuth 2.0 convention. */
  public static LoginResponse bearer(String accessToken, long expiresIn, User user) {
    return new LoginResponse(
        accessToken,
        "Bearer",
        expiresIn,
        user.getEmail(),
        user.getDisplayName(),
        user.getRoles().stream().map(Role::getName).toList());
  }
}
