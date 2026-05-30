package com.thedogs.modules.auth;

import com.thedogs.modules.auth.exception.InvalidRefreshTokenException;
import com.thedogs.modules.auth.exception.RefreshTokenExpiredException;
import com.thedogs.modules.auth.exception.RefreshTokenReusedException;
import com.thedogs.modules.auth.exception.RefreshTokenRevokedException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
   * Self-reference to the Spring proxy of this bean. Required so that calling {@link
   * #revokeFamily(UUID)} from within {@link #rotateToken} goes through the proxy and honours the
   * {@code REQUIRES_NEW} transaction propagation.
   *
   * <p>Injected lazily to avoid a circular-dependency bootstrap problem. The setter is
   * package-private so tests can wire a mock or spy if needed without reflection.
   */
  @Setter(onMethod_ = {@Autowired, @Lazy})
  private RefreshTokenService self;

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
   * family, and returns the new entity.
   *
   * <p>Theft detection: if the old token was already used, every active token in the family is
   * revoked and {@link RefreshTokenReusedException} is thrown. Revocation is committed in a
   * separate transaction ({@code REQUIRES_NEW}) via {@link #revokeFamily}, called through the
   * Spring proxy (via {@link #self}) so the propagation is respected. The two-phase read strategy
   * (non-locking check first, locking read second) prevents a deadlock between the outer
   * transaction's SELECT FOR UPDATE and the inner transaction's UPDATE during revocation.
   *
   * <p>Concurrency safety for valid rotation: the {@code SELECT FOR UPDATE} is acquired only after
   * confirming the token appears active (non-locking check passes). The lock ensures that two
   * concurrent threads rotating the same fresh token see one succeed and one detect reuse.
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

    // Phase 1: Non-locking read — fast path to detect obviously invalid tokens.
    // If the token is already used or doesn't exist, handle it here WITHOUT acquiring a lock.
    // This avoids a deadlock that would occur if we held a SELECT FOR UPDATE lock on the token
    // row while also trying to UPDATE it inside a REQUIRES_NEW transaction (revokeFamily).
    RefreshToken earlyCheck =
        repository.findByTokenHash(oldHash).orElseThrow(InvalidRefreshTokenException::new);

    if (earlyCheck.getUsedAt() != null) {
      // Theft detected — the token was already consumed in a previous rotation.
      // Call revokeFamily through the Spring proxy so REQUIRES_NEW takes effect and the
      // revocation is committed even though this method's transaction will be rolled back.
      // In unit tests, self is null (no Spring context); fall back to direct call which is fine
      // because unit tests mock the repository and do not use real transactions.
      log.warn(
          "event=refresh_token_reuse_detected familyId={} userId={}",
          earlyCheck.getFamilyId(),
          earlyCheck.getUserId());
      RefreshTokenService proxy = (self != null) ? self : this;
      proxy.revokeFamily(earlyCheck.getFamilyId());
      throw new RefreshTokenReusedException();
    }

    // Phase 2: Locking read — acquire SELECT FOR UPDATE to serialise concurrent rotations.
    // Re-read with lock to prevent two threads from both seeing the token as unused.
    RefreshToken old =
        repository.findByTokenHashForUpdate(oldHash).orElseThrow(InvalidRefreshTokenException::new);

    // Re-check after acquiring the lock (another thread may have rotated it between phases 1 and 2)
    if (old.getUsedAt() != null) {
      log.warn(
          "event=refresh_token_reuse_detected familyId={} userId={}",
          old.getFamilyId(),
          old.getUserId());
      // Cannot call REQUIRES_NEW here — we hold the lock (deadlock risk).
      // Fall back to same-transaction revocation; the lock owner can always update the locked row.
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
   * <p>Annotated with {@code REQUIRES_NEW} so that when called from the theft-detection path in
   * {@link #rotateToken} (Phase 1 — no lock held), the revocation commits independently of the
   * outer transaction. This ensures family revocation persists even when the caller's transaction
   * subsequently rolls back due to the thrown {@link RefreshTokenReusedException}.
   *
   * <p>Must always be called through the Spring proxy for the {@code REQUIRES_NEW} propagation to
   * take effect (either from an external bean, or via {@link #self} from within this class).
   *
   * @param familyId the family to revoke
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
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
