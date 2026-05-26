package com.thedogs.modules.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** AUTH-03: Repository for refresh token persistence, lookup, and family-level revocation. */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * Finds an active token by its hash. A token is active when it has not been used, not been
   * revoked, and has not expired.
   *
   * <p>Used on the happy-path refresh: if this returns empty the token is immediately invalid.
   */
  @Query(
      "SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash "
          + "AND r.revokedAt IS NULL AND r.expiresAt > :now AND r.usedAt IS NULL")
  Optional<RefreshToken> findActiveByHash(@Param("hash") String hash, @Param("now") Instant now);

  /**
   * Finds any token by its hash (including used/revoked/expired). Used for theft detection: if a
   * token was found here but not in {@link #findActiveByHash}, it means the token was already used
   * or revoked.
   */
  @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash")
  Optional<RefreshToken> findAnyByHash(@Param("hash") String hash);

  /**
   * Acquires a pessimistic write lock on a token row before marking it used. Prevents two
   * concurrent refresh requests from both succeeding with the same token (double-spend attack).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash")
  Optional<RefreshToken> findForUpdateByHash(@Param("hash") String hash);

  /**
   * Revokes all active tokens in the given family. Called on theft detection (used token
   * presented) to invalidate the entire session lineage.
   */
  @Modifying
  @Transactional
  @Query(
      "UPDATE RefreshToken r SET r.revokedAt = :now "
          + "WHERE r.familyId = :familyId AND r.revokedAt IS NULL")
  void revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

  /**
   * Deletes tokens that are either explicitly revoked (revoked_at older than cutoff) or silently
   * expired without being used (expires_at older than cutoff and still not revoked). Run by the
   * nightly cleanup scheduler.
   */
  @Modifying
  @Transactional
  @Query(
      "DELETE FROM RefreshToken r "
          + "WHERE r.revokedAt < :cutoff "
          + "OR (r.expiresAt < :cutoff AND r.revokedAt IS NULL)")
  void deleteExpiredAndRevoked(@Param("cutoff") Instant cutoff);
}
