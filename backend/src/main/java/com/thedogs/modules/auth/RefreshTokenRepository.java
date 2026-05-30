package com.thedogs.modules.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  /**
   * Finds an active (not revoked, not expired, not used) refresh token by its hash. Used in the
   * normal refresh path.
   */
  @Query(
      "SELECT r FROM RefreshToken r"
          + " WHERE r.tokenHash = :hash"
          + "   AND r.revokedAt IS NULL"
          + "   AND r.expiresAt > :now"
          + "   AND r.usedAt IS NULL")
  Optional<RefreshToken> findActiveByHash(@Param("hash") String hash, @Param("now") Instant now);

  /**
   * Finds any token row by hash regardless of state (used, revoked, expired). Needed for theft
   * detection — we must see even consumed rows to detect replay.
   */
  Optional<RefreshToken> findByTokenHash(String tokenHash);

  /**
   * Finds all active (not yet revoked) tokens belonging to a family. Used when revoking an entire
   * family on logout or theft detection.
   */
  @Query(
      "SELECT r FROM RefreshToken r"
          + " WHERE r.familyId = :familyId"
          + "   AND r.revokedAt IS NULL")
  List<RefreshToken> findActiveByFamilyId(@Param("familyId") UUID familyId);

  /**
   * Fetches a token by hash with a pessimistic write lock (SELECT FOR UPDATE). Prevents two
   * concurrent requests from both seeing the token as unused and both succeeding — only one
   * transaction gets the lock; the other either waits or fails.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT r FROM RefreshToken r WHERE r.tokenHash = :hash")
  Optional<RefreshToken> findByTokenHashForUpdate(@Param("hash") String hash);
}
