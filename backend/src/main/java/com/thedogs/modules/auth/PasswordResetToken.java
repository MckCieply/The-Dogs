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
 * Persistent password-reset token row (AUTH-05, ADR-0013). The token value itself is never stored —
 * only its SHA-256 hex hash. The raw value leaves the backend in exactly two places: the dev-mode
 * log helper (off by default) and the ROLE_ADMIN retrieval endpoint.
 *
 * <p>Does NOT extend BaseEntity: the table uses issued_at as its creation timestamp, mirroring
 * refresh_token.
 */
@Entity
@Table(name = "password_reset_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class PasswordResetToken {

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

  /** Set when the token is consumed by a successful reset OR superseded by a newer request. */
  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "requested_ip_bucket")
  private String requestedIpBucket;
}
