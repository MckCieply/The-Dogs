package com.thedogs.modules.auth;

import com.thedogs.modules.auth.exception.InvalidRefreshTokenException;
import com.thedogs.modules.auth.exception.RefreshTokenExpiredException;
import com.thedogs.modules.auth.exception.RefreshTokenRevokedException;
import com.thedogs.modules.auth.exception.RefreshTokenReusedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages the lifecycle of persistent refresh tokens: issuance, rotation (with theft detection),
 * and family revocation. See docs/specs/auth-refresh-logout.md.
 *
 * <p>Security invariants:
 *
 * <ul>
 *   <li>Only the SHA-256 hash of the token value is persisted — never the raw value.
 *   <li>rotateToken uses SELECT FOR UPDATE to prevent concurrent double-rotation.
 *   <li>Any reuse of a consumed token triggers full family revocation (OWASP rotation pattern).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class RefreshTokenService {

  private final RefreshTokenRepository repository;
  private final TokenService tokenService;

  /**
   * Issues and persists a new refresh token row. Called from AuthService.login().
   *
   * @param userId the authenticated user
   * @param expiresAt absolute expiry derived from jwt.refresh-token-ttl-days
   * @param familyId UUID for the token family (new UUID on login, inherited on rotation)
   * @param parentId id of the token being rotated (null on first issuance)
   * @param userAgent User-Agent header for forensic metadata (may be null)
   * @param ipBucket masked IP bucket for forensic metadata (may be null)
   * @param tokenHash hex SHA-256 of the raw token value
   * @return the persisted RefreshToken entity
   */
  public RefreshToken issue(
      UUID userId,
      Instant expiresAt,
      UUID familyId,
      UUID parentId,
      String userAgent,
      String ipBucket,
      String tokenHash) {

    RefreshToken token =
        RefreshToken.builder()
            .userId(userId)
            .familyId(familyId)
            .parentId(parentId)
            .tokenHash(tokenHash)
            .issuedAt(Instant.now())
            .expiresAt(expiresAt)
            .userAgent(userAgent)
            .ipBucket(ipBucket)
            .build();

    return repository.save(token);
  }

  /**
   * Rotates an existing refresh token: marks the old one as used, creates a new one in the same
   * family, and returns the new entity. Uses SELECT FOR UPDATE to serialise concurrent requests
   * against the same token.
   *
   * <p>Theft detection: if the old token was already used, every active token in the family is
   * revoked and {@link RefreshTokenReusedException} is thrown.
   *
   * @param oldTokenValue raw (unhashed) value from the cookie
   * @param newTokenValue raw (unhashed) value for the replacement token
   * @return the newly created RefreshToken entity
   * @throws InvalidRefreshTokenException if the hash does not match any row
   * @throws RefreshTokenReusedException if the token was already consumed (theft detected)
   * @throws RefreshTokenRevokedException if the token was revoked
   * @throws RefreshTokenExpiredException if the token's expiry time has passed
   */
  public RefreshToken rotateToken(String oldTokenValue, String newTokenValue) {
    String oldHash = sha256Hex(oldTokenValue);
    String newHash = sha256Hex(newTokenValue);

    // SELECT FOR UPDATE — serialises concurrent requests on the same token row.
    // If no row exists for this hash the token is entirely unknown.
    RefreshToken old =
        repository
            .findByTokenHashForUpdate(oldHash)
            .orElseThrow(InvalidRefreshTokenException::new);

    // Theft detection: token was already used in a previous rotation
    if (old.getUsedAt() != null) {
      log.warn(
          "event=refresh_token_reuse_detected familyId={} userId={}", old.getFamilyId(), old.getUserId());
      revokeFamily(old.getFamilyId());
      throw new RefreshTokenReusedException();
    }

    if (old.getRevokedAt() != null) {
      throw new RefreshTokenRevokedException();
    }

    if (old.getExpiresAt().isBefore(Instant.now())) {
      throw new RefreshTokenExpiredException();
    }

    // Mark old token as consumed
    old.setUsedAt(Instant.now());
    repository.save(old);

    // Issue new token in the same family, linked to the old one as parent
    Instant newExpiry = Instant.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds());
    return issue(
        old.getUserId(),
        newExpiry,
        old.getFamilyId(),
        old.getId(),
        old.getUserAgent(),
        old.getIpBucket(),
        newHash);
  }

  /**
   * Revokes all active tokens belonging to the given family. Idempotent — already-revoked tokens
   * are ignored.
   *
   * @param familyId the family to revoke
   */
  public void revokeFamily(UUID familyId) {
    List<RefreshToken> active = repository.findActiveByFamilyId(familyId);
    Instant now = Instant.now();
    for (RefreshToken t : active) {
      t.setRevokedAt(now);
    }
    repository.saveAll(active);
    log.info("event=refresh_family_revoked familyId={} count={}", familyId, active.size());
  }

  /**
   * Scheduled cleanup of expired and revoked tokens older than 30 days.
   *
   * <p>TODO(ops): tune the cron expression and retention window via application config before
   * production. Current scaffold fires daily at 03:00 server time.
   */
  @Scheduled(cron = "0 0 3 * * *")
  @Transactional
  public void cleanupExpiredTokens() {
    // TODO(ops): implement DELETE FROM refresh_token WHERE
    //   (expires_at < now() - INTERVAL '30 days') OR
    //   (revoked_at < now() - INTERVAL '30 days')
    // Use a native query or @Modifying JPQL to batch-delete efficiently.
    log.info("event=refresh_token_cleanup_skipped reason=not_yet_implemented");
  }

  /** Computes hex-encoded SHA-256 of the input string. Never log the input or output. */
  static String sha256Hex(String input) {
    try {
      java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
