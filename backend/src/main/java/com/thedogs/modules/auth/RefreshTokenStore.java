package com.thedogs.modules.auth;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory refresh token store for the MVP. Replace with a database-backed store (with a
 * refresh_tokens table) before going to production to survive restarts.
 */
@Component
public class RefreshTokenStore {

  private final Map<String, UUID> tokenToUserId = new ConcurrentHashMap<>();

  public void store(String token, UUID userId) {
    tokenToUserId.put(token, userId);
  }

  public Optional<UUID> getUserId(String token) {
    return Optional.ofNullable(tokenToUserId.get(token));
  }

  public void revoke(String token) {
    tokenToUserId.remove(token);
  }
}
