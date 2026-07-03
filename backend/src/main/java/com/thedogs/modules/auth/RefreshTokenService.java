package com.thedogs.modules.auth;

import com.thedogs.modules.auth.exception.InvalidRefreshTokenException;
import com.thedogs.modules.auth.exception.RefreshTokenExpiredException;
import com.thedogs.modules.auth.exception.RefreshTokenReusedException;
import com.thedogs.modules.auth.exception.RefreshTokenRevokedException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 *   <li>rotateToken uses an atomic conditional UPDATE to prevent concurrent double-rotation.
 *   <li>Any reuse of a consumed token triggers full family revocation (OWASP rotation pattern).
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

  private final RefreshTokenRepository repository;
  private final TokenService tokenService;

  /**
   * Self-reference to the Spring proxy of this bean. Required so that {@link #rotateToken} —
   * deliberately non-transactional — can invoke {@link #rotateInTransaction} through the proxy,
   * making its {@code @Transactional} annotation take effect.
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
  @Transactional
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
   * revoked and {@link RefreshTokenReusedException} is thrown. The revocation is committed by
   * {@link #rotateInTransaction} <em>before</em> this method throws — which is why this method is
   * deliberately NOT transactional: throwing from inside the transaction would roll the revocation
   * back.
   *
   * <p>History: an earlier design revoked in a {@code REQUIRES_NEW} inner transaction instead.
   * That self-deadlocks in the concurrent race path — the outer transaction's failed conditional
   * UPDATE ({@link RefreshTokenRepository#markUsedIfActive}) still holds a row lock on the old
   * token (PostgreSQL retains the tuple lock even when the re-checked WHERE clause no longer
   * matches), so the inner transaction's UPDATE of that same row blocks until lock timeout — or
   * forever with the PostgreSQL default of no timeout, observed as ci-backend hanging until the
   * 45-minute job limit.
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
    RefreshTokenService proxy = (self != null) ? self : this;
    return proxy
        .rotateInTransaction(oldTokenValue, newTokenValue)
        .orElseThrow(RefreshTokenReusedException::new);
  }

  /**
   * Transactional core of rotation. Returns the new token, or {@link Optional#empty()} when token
   * reuse was detected — in that case the entire family has been revoked <em>in this
   * transaction</em>, so the revocation commits before {@link #rotateToken} translates the empty
   * result into a {@link RefreshTokenReusedException}.
   *
   * <p>{@code REQUIRES_NEW} so the commit is independent of any caller-managed transaction: the
   * theft-detection contract is "revocation is durable even though the caller then sees an
   * exception", which joining a caller's transaction would silently break (the caller's rollback
   * would undo the revocation). The race-path lock on the old token's row is taken by THIS
   * transaction, and revokeFamily joins it — a transaction never blocks on its own locks.
   *
   * <p>Public only so the Spring proxy can apply {@code @Transactional}; call {@link #rotateToken}
   * instead.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<RefreshToken> rotateInTransaction(String oldTokenValue, String newTokenValue) {
    String oldHash = sha256Hex(oldTokenValue);
    String newHash = sha256Hex(newTokenValue);
    Instant now = Instant.now();

    // Phase 1: Read to detect obviously invalid tokens (unknown, already-used, revoked, expired).
    RefreshToken earlyCheck =
        repository.findByTokenHash(oldHash).orElseThrow(InvalidRefreshTokenException::new);

    if (earlyCheck.getUsedAt() != null) {
      // Theft detected — token was already consumed in a prior rotation.
      log.warn(
          "event=refresh_token_reuse_detected familyId={} userId={}",
          earlyCheck.getFamilyId(),
          earlyCheck.getUserId());
      revokeFamily(earlyCheck.getFamilyId());
      return Optional.empty();
    }
    if (earlyCheck.getRevokedAt() != null) {
      throw new RefreshTokenRevokedException();
    }
    if (earlyCheck.getExpiresAt().isBefore(now)) {
      throw new RefreshTokenExpiredException();
    }

    // Phase 2: Atomic conditional UPDATE — "mark as used only if still active".
    // Returns 1 if this caller won the race, 0 if another request already rotated or revoked it.
    // Because the UPDATE is atomic at the DB level, no SELECT FOR UPDATE lock is needed.
    int updated = repository.markUsedIfActive(oldHash, now);

    if (updated == 0) {
      // Another concurrent request just rotated (or revoked) this token between Phase 1 and here.
      // Re-read the current state to determine the right error code. Revoking in THIS transaction
      // is safe even though our failed conditional UPDATE holds a lock on the old token's row —
      // a transaction never blocks on its own locks.
      RefreshToken current =
          repository.findByTokenHash(oldHash).orElseThrow(InvalidRefreshTokenException::new);
      log.warn(
          "event=refresh_token_reuse_detected familyId={} userId={}",
          current.getFamilyId(),
          current.getUserId());
      revokeFamily(current.getFamilyId());
      return Optional.empty();
    }

    // Issue new token in the same family, linked to the old one as parent
    Instant newExpiry = now.plusSeconds(tokenService.getRefreshTokenTtlSeconds());
    return Optional.of(
        issue(
            earlyCheck.getUserId(),
            newExpiry,
            earlyCheck.getFamilyId(),
            earlyCheck.getId(),
            earlyCheck.getUserAgent(),
            earlyCheck.getIpBucket(),
            newHash));
  }

  /**
   * Revokes all active tokens belonging to the given family. Idempotent — already-revoked tokens
   * are ignored.
   *
   * <p>Joins the caller's transaction. In the theft-detection path the revocation is persisted
   * because {@link #rotateInTransaction} returns normally (committing) and the 401 exception is
   * thrown only afterwards, outside the transaction, by {@link #rotateToken}.
   *
   * @param familyId the family to revoke
   */
  @Transactional
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
   * Revokes the token family associated with the given raw token value. Idempotent — if the token
   * is not found or is already revoked the method returns without error.
   *
   * <p>Hashes the raw value before querying so the plaintext is never stored or logged.
   *
   * @param rawTokenValue the raw (unhashed) refresh token from the cookie
   */
  @Transactional
  public void logout(String rawTokenValue) {
    String hash = sha256Hex(rawTokenValue);
    repository
        .findByTokenHash(hash)
        .ifPresent(
            token -> {
              if (token.getRevokedAt() == null) {
                revokeFamily(token.getFamilyId());
              }
            });
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
