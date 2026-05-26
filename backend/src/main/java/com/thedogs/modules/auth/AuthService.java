package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AUTH-02 / AUTH-03: Core authentication service.
 *
 * <p>Refresh tokens are persisted in the {@code refresh_token} table. The cleartext 256-bit token
 * value is sent to the client via HttpOnly cookie; only the SHA-256 hash of the raw bytes is stored
 * in the database (AC-10: never log the hash or the value).
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

  private final TokenService tokenService;
  private final RefreshTokenRepository refreshTokenRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final IpRateLimiter ipRateLimiter;
  private final LoginAuditLogger auditLogger;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * Pre-computed dummy hash used for timing-safe comparison when the email is not found. BCrypt
   * compare on a known hash ensures the wall-clock time for an unknown-email response is
   * indistinguishable from a wrong-password response, preventing user-enumeration via timing.
   * Computed eagerly at construction time so the first unknown-email request is not measurably
   * slower than subsequent ones.
   */
  private final String dummyHash;

  public AuthService(
      TokenService tokenService,
      RefreshTokenRepository refreshTokenRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      IpRateLimiter ipRateLimiter,
      LoginAuditLogger auditLogger) {
    this.tokenService = tokenService;
    this.refreshTokenRepository = refreshTokenRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.ipRateLimiter = ipRateLimiter;
    this.auditLogger = auditLogger;
    this.dummyHash = passwordEncoder.encode("dummy-timing-password-for-unknown-email");
  }

  public record LoginResult(LoginResponse loginResponse, String refreshToken) {}

  public record RefreshResult(RefreshResponse refreshResponse, String refreshToken) {}

  /**
   * Authenticates the user with anti-enumeration, rate limiting, and audit logging.
   *
   * <p>Rate limiting: the IP is checked for exhausted tokens BEFORE doing bcrypt so that already-
   * blocked IPs get an immediate 429 without triggering an expensive hash comparison. When
   * authentication fails the IP consumes a token from the bucket; successful logins do NOT consume.
   *
   * <p>On success, persists a refresh token row. Only the SHA-256 hash of the raw bytes is stored.
   *
   * @param request login credentials
   * @param clientIp extracted client IP (CF-Connecting-IP / X-Forwarded-For / RemoteAddr)
   * @throws TooManyAttemptsException if the IP has exceeded the 5-attempts-per-15-min limit
   * @throws BadCredentialsException on wrong password or unknown email (identical body anti-enum)
   * @throws DisabledException if the account exists but is disabled
   */
  @PreAuthorize("permitAll()")
  @Transactional
  public LoginResult login(LoginRequest request, String clientIp) {
    // Block already-exhausted IPs before doing any bcrypt work
    ipRateLimiter.checkNotRateLimited(clientIp);

    Optional<User> userOpt = userRepository.findByEmail(request.email().toLowerCase());

    if (userOpt.isEmpty()) {
      // Timing-safe: perform a dummy bcrypt comparison so response time does not reveal
      // whether the email exists in the database (anti-enumeration, AC-2).
      passwordEncoder.matches(request.password(), dummyHash);
      // Consume a rate-limit token for this failed attempt
      ipRateLimiter.recordFailedAttempt(clientIp);
      auditLogger.logFailure(request.email(), clientIp, "bad_credentials");
      throw new BadCredentialsException("bad_credentials");
    }

    User user = userOpt.get();

    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      ipRateLimiter.recordFailedAttempt(clientIp);
      auditLogger.logFailure(request.email(), clientIp, "bad_credentials");
      throw new BadCredentialsException("bad_credentials");
    }

    // Account disabled check comes after password validation to avoid leaking account state
    // to callers who don't know the correct password (anti-enumeration).
    if (!user.isEnabled()) {
      auditLogger.logFailure(request.email(), clientIp, "account_disabled");
      throw new DisabledException("account_disabled");
    }

    String accessToken = tokenService.generateAccessToken(user);
    // Issue a new family: this is the first token in the rotation chain
    String tokenValue = persistNewRefreshToken(user.getId(), null, UUID.randomUUID(), clientIp);

    auditLogger.logSuccess(request.email(), clientIp);
    return new LoginResult(
        new LoginResponse(accessToken, "Bearer", tokenService.getAccessTokenTtlSeconds()),
        tokenValue);
  }

  /**
   * Rotates a refresh token. Issues a new refresh token row (inheriting the family) and a new
   * access JWT.
   *
   * <p>Concurrent refresh protection: acquires a pessimistic write lock on the old token row before
   * marking it used, so two concurrent requests with the same token cannot both succeed.
   *
   * <p>Theft detection: if the token hash is found but {@code used_at} is already set, the entire
   * family is revoked and {@link RefreshTokenException#reused()} is thrown.
   *
   * @param cookieValue raw base64url token value from the HttpOnly cookie
   * @param clientIp extracted client IP (for ip_bucket on the new token row)
   * @throws RefreshTokenException on invalid, expired, revoked, or reused token
   */
  @PreAuthorize("permitAll()")
  @Transactional
  public RefreshResult refresh(String cookieValue, String clientIp) {
    String hash = sha256HexFromBase64Url(cookieValue);

    // Fast check before acquiring lock: if the token is not in the DB at all invalid
    RefreshToken existing =
        refreshTokenRepository.findAnyByHash(hash).orElseThrow(RefreshTokenException::invalid);

    // Theft detection: token already used revoke entire family, throw reused
    if (existing.getUsedAt() != null) {
      refreshTokenRepository.revokeFamily(existing.getFamilyId(), Instant.now());
      throw RefreshTokenException.reused();
    }

    // Check revocation before acquiring lock (cheap guard)
    if (existing.getRevokedAt() != null) {
      throw RefreshTokenException.revoked();
    }

    if (existing.getExpiresAt().isBefore(Instant.now())) {
      throw RefreshTokenException.expired();
    }

    // Acquire pessimistic write lock to prevent double-spend on concurrent refresh
    RefreshToken locked =
        refreshTokenRepository
            .findForUpdateByHash(hash)
            .orElseThrow(RefreshTokenException::invalid);

    // Re-validate state after acquiring lock (concurrent request may have already processed)
    if (locked.getUsedAt() != null) {
      refreshTokenRepository.revokeFamily(locked.getFamilyId(), Instant.now());
      throw RefreshTokenException.reused();
    }
    if (locked.getRevokedAt() != null) {
      throw RefreshTokenException.revoked();
    }

    // Mark old token as used (consumed by this rotation)
    locked.setUsedAt(Instant.now());
    refreshTokenRepository.save(locked);

    // Load user for new access token generation
    User user =
        userRepository
            .findById(locked.getUserId())
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    // Issue new token inheriting the family
    String newTokenValue =
        persistNewRefreshToken(locked.getUserId(), locked.getId(), locked.getFamilyId(), clientIp);
    String newAccessToken = tokenService.generateAccessToken(user);

    return new RefreshResult(
        new RefreshResponse(newAccessToken, tokenService.getAccessTokenTtlSeconds()),
        newTokenValue);
  }

  /**
   * Revokes the refresh token family associated with the given cookie value. Idempotent: returns
   * normally even if the token is missing, already revoked, or expired.
   *
   * @param cookieValue raw base64url token value from the HttpOnly cookie
   */
  @PreAuthorize("permitAll()")
  @Transactional
  public void logout(String cookieValue) {
    String hash;
    try {
      hash = sha256HexFromBase64Url(cookieValue);
    } catch (IllegalArgumentException e) {
      // Malformed cookie value treat as missing (idempotent logout)
      return;
    }

    refreshTokenRepository
        .findAnyByHash(hash)
        .filter(t -> t.getRevokedAt() == null)
        .ifPresent(t -> refreshTokenRepository.revokeFamily(t.getFamilyId(), Instant.now()));
  }

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  /**
   * Generates a new refresh token raw value, persists it hashed, and returns the raw base64url
   * value for the client cookie.
   *
   * <p>Token value = 256 random bits encoded as base64url without padding. Stored hash = SHA-256 of
   * the raw bytes (not the base64 string), hex-encoded.
   *
   * @param userId owner of this token
   * @param parentId UUID of the predecessor token (null for initial login)
   * @param familyId family group UUID (new UUID on login, inherited on rotation)
   * @param clientIp for coarse ip_bucket storage
   * @return the raw base64url token value to send to the client
   */
  private String persistNewRefreshToken(
      UUID userId, UUID parentId, UUID familyId, String clientIp) {
    byte[] randomBytes = new byte[32];
    secureRandom.nextBytes(randomBytes);
    // AC-10: never log tokenValue or tokenHash
    String tokenValue = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    String tokenHash = sha256Hex(randomBytes);

    Instant now = Instant.now();
    RefreshToken refreshToken =
        RefreshToken.builder()
            .id(UUID.randomUUID())
            .familyId(familyId)
            .parentId(parentId)
            .userId(userId)
            .tokenHash(tokenHash)
            .issuedAt(now)
            .expiresAt(now.plus(tokenService.getRefreshTokenTtlDays(), ChronoUnit.DAYS))
            .ipBucket(toIpBucket(clientIp))
            .build();

    refreshTokenRepository.save(refreshToken);
    return tokenValue;
  }

  /**
   * Decodes a base64url token value and computes the SHA-256 hex digest of the raw bytes.
   *
   * @param base64UrlValue raw base64url-encoded token (no padding)
   * @return 64-character lowercase hex string
   * @throws IllegalArgumentException if the value is not valid base64url
   */
  static String sha256HexFromBase64Url(String base64UrlValue) {
    byte[] rawBytes = Base64.getUrlDecoder().decode(base64UrlValue);
    return sha256Hex(rawBytes);
  }

  /**
   * Computes the SHA-256 hex digest of the given bytes.
   *
   * @param bytes raw bytes (e.g., the 32-byte token value)
   * @return 64-character lowercase hex string
   */
  static String sha256Hex(byte[] bytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(bytes);
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be present in every JVM (JCA mandated algorithm)
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

  /**
   * Returns a coarse IP bucket (first two octets for IPv4, first group for IPv6) suitable for
   * anomaly detection without storing full PII.
   */
  private static String toIpBucket(String ip) {
    if (ip == null || ip.isBlank()) return null;
    String[] parts = ip.split("\\.");
    if (parts.length >= 2) {
      return parts[0] + "." + parts[1];
    }
    // IPv6 or single-component use first segment only
    int colon = ip.indexOf(':');
    return colon > 0 ? ip.substring(0, colon) : ip;
  }
}
