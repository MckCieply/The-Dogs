package com.thedogs.modules.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Persistent email-verification token row (AUTH-09). Same shape and posture as {@link
 * PasswordResetToken}: the token value is never stored — only its SHA-256 hex hash — and the raw
 * value leaves the backend only via the dev-mode log helper (off by default) and the ROLE_ADMIN
 * retrieval endpoint.
 *
 * <p>Does NOT extend BaseEntity: the table uses issued_at as its creation timestamp, mirroring
 * refresh_token and password_reset_token.
 */
@Entity
@Table(name = "email_verification_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class EmailVerificationToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @ToString.Include
  private UUID id;

  @ToString.Include
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /** Hex-encoded SHA-256 hash of the opaque token value. Never include in log output. */
  @Column(name = "token_hash", nullable = false, length = 64, unique = true)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** Set when the token is consumed by a successful confirmation OR superseded by a newer one. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "requested_ip_bucket")
  private String requestedIpBucket;
}
