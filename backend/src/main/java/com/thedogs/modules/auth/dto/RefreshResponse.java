package com.thedogs.modules.auth.dto;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import java.util.List;

/**
 * Response body for POST /api/v1/auth/refresh. Includes the identity fields so a client that lost
 * its in-memory state (hard reload — access tokens are memory-only per ADR-0003) can restore the
 * whole session from the refresh cookie alone.
 */
public record RefreshResponse(
    String accessToken, long expiresIn, String email, String displayName, List<String> roles) {

  public static RefreshResponse of(String accessToken, long expiresIn, User user) {
    return new RefreshResponse(
        accessToken,
        expiresIn,
        user.getEmail(),
        user.getDisplayName(),
        user.getRoles().stream().map(Role::getName).toList());
  }
}
