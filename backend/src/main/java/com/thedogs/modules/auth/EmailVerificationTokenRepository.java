package com.thedogs.modules.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, UUID> {

  Optional<EmailVerificationToken> findByTokenHash(String tokenHash);

  /** All not-yet-used tokens for a user — superseded on each new issuance (resend/admin fetch). */
  @Query("SELECT t FROM EmailVerificationToken t WHERE t.userId = :userId AND t.usedAt IS NULL")
  List<EmailVerificationToken> findActiveByUserId(@Param("userId") UUID userId);

  /**
   * The single active (unused, unexpired) token for a user, for the admin retrieval endpoint.
   * Ordered defensively so a data anomaly with two active rows still returns the newest.
   */
  @Query(
      "SELECT t FROM EmailVerificationToken t"
          + " WHERE t.userId = :userId AND t.usedAt IS NULL AND t.expiresAt > :now"
          + " ORDER BY t.issuedAt DESC")
  List<EmailVerificationToken> findActiveUnexpiredByUserId(
      @Param("userId") UUID userId, @Param("now") Instant now);

  /**
   * Atomically consumes a verification token: marks it used only if it is still unused and
   * unexpired. Returns 1 when this caller won, 0 when the token was already used, expired, or
   * unknown — mirroring PasswordResetTokenRepository#markUsedIfActive.
   */
  @Modifying(clearAutomatically = true)
  @Query(
      "UPDATE EmailVerificationToken t"
          + "   SET t.usedAt = :now"
          + " WHERE t.tokenHash = :hash"
          + "   AND t.usedAt   IS NULL"
          + "   AND t.expiresAt > :now")
  int markUsedIfActive(@Param("hash") String hash, @Param("now") Instant now);
}
