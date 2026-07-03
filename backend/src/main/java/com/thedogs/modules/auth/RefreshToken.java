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
 * Persistent refresh token row. The token value itself is never stored — only its SHA-256 hex hash
 * (tokenHash). The raw value is sent to the client in an HttpOnly cookie only once at issuance.
 *
 * <p>Does NOT extend BaseEntity: the refresh_token table does not have separate created_at /
 * updated_at audit columns — it uses issued_at as its creation timestamp. See
 * docs/specs/auth-refresh-logout.md and ADR-0003.
 */
@Entity
@Table(name = "refresh_token")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
@ToString(onlyExplicitlyIncluded = true)
public class RefreshToken {

  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @ToString.Include
  private UUID id;

  @ToString.Include
  @Column(name = "family_id", nullable = false)
  private UUID familyId;

  /** Parent token's id — null for the first token in a family (issued at login). */
  @Column(name = "parent_id")
  private UUID parentId;

  @ToString.Include
  @Column(name = "user_id", nullable = false)
  private UUID userId;

  /**
   * Hex-encoded SHA-256 hash of the opaque token value. VARCHAR(64) — never include in log output.
   */
  @Column(name = "token_hash", nullable = false, length = 64, unique = true)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  /** Set when the token is consumed during a rotation — used for theft detection. */
  @Column(name = "used_at")
  private Instant usedAt;

  /** Set when the token (or its family) is explicitly revoked via logout or theft detection. */
  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "user_agent")
  private String userAgent;

  /** Masked IP bucket: /24 for IPv4, /64 for IPv6. */
  @Column(name = "ip_bucket")
  private String ipBucket;

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RefreshToken other)) return false;
    return id != null && id.equals(other.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }
}
