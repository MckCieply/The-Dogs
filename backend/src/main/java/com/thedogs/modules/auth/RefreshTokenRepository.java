package com.thedogs.modules.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
   * Atomically marks a refresh token as used via a conditional UPDATE. Returns 1 if the row was
   * updated (this caller won the race), or 0 if another request already rotated the token (the row
   * existed but {@code usedAt} was already set, or it was revoked/expired).
   *
   * <p>Using a conditional UPDATE is the simplest race-free way to implement "exactly-one rotation
   * wins": no SELECT FOR UPDATE lock is held across the revokeFamily call, which eliminates the
   * deadlock that would otherwise occur when the REQUIRES_NEW inner transaction tries to UPDATE the
   * same locked row.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE RefreshToken r"
          + "   SET r.usedAt = :now"
          + " WHERE r.tokenHash = :hash"
          + "   AND r.usedAt   IS NULL"
          + "   AND r.revokedAt IS NULL"
          + "   AND r.expiresAt > :now")
  int markUsedIfActive(
      @Param("hash") String hash, @Param("now") Instant now);
}
