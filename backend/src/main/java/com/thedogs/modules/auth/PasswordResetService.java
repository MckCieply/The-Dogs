package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.AdminResetTokenResponse;
import com.thedogs.modules.auth.exception.InvalidResetTokenException;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Password reset without SMTP (AUTH-05, ADR-0013): tokens are minted by forgot-password, retrieved
 * out-of-band by an admin, and consumed by reset-password. Token values are stored only as SHA-256
 * hashes.
 *
 * <p>Spec drift note: ADR-0013's data model stored the raw token so the admin endpoint could return
 * it, while the AUTH-05 spec mandates hashing at rest. This implementation keeps hashing at rest
 * and resolves the conflict by having the admin retrieval endpoint ROTATE the active token — it
 * revokes the outstanding token, mints a fresh one, and returns the fresh value. Every admin fetch
 * therefore invalidates prior values, which is a strictly stronger posture than replaying a stored
 * plaintext.
 */
@Service
@Slf4j
public class PasswordResetService {

  static final Duration TOKEN_TTL = Duration.ofHours(1);

  /** Constant-time floor for forgot-password (AC-2): both branches pad to this duration. */
  static final long FORGOT_FLOOR_MILLIS = 80;

  private final UserRepository userRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final RefreshTokenRepository refreshTokenRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordStrengthValidator passwordStrengthValidator;
  private final PasswordResetRateLimiter rateLimiter;
  private final TokenService tokenService;
  private final boolean logTokenOnCreate;

  public PasswordResetService(
      UserRepository userRepository,
      PasswordResetTokenRepository tokenRepository,
      RefreshTokenRepository refreshTokenRepository,
      PasswordEncoder passwordEncoder,
      PasswordStrengthValidator passwordStrengthValidator,
      PasswordResetRateLimiter rateLimiter,
      TokenService tokenService,
      @Value("${app.password-reset.log-token-on-create:false}") boolean logTokenOnCreate) {
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.passwordEncoder = passwordEncoder;
    this.passwordStrengthValidator = passwordStrengthValidator;
    this.rateLimiter = rateLimiter;
    this.tokenService = tokenService;
    this.logTokenOnCreate = logTokenOnCreate;
  }

  /**
   * Creates a reset token for the given email if an account exists; always behaves identically to
   * the caller (204, and wall-clock padded to {@link #FORGOT_FLOOR_MILLIS}) whether or not the
   * email is known — anti-enumeration (AC-1, AC-2).
   *
   * <p>Transactional so supersede-old + insert-new is atomic. The padding sleep runs inside the
   * transaction, holding a connection for up to 80 ms — acceptable at this traffic level and
   * simpler than splitting the transaction boundary.
   */
  @Transactional
  public void forgotPassword(String email, String ipBucket) {
    long start = System.nanoTime();

    String emailHash = sha256Hex(email.toLowerCase(Locale.ROOT));
    // Bucket keyed by email hash: exists (and throttles) even for unknown emails (AC-11).
    rateLimiter.recordForgotAttempt(emailHash);

    Optional<User> userOpt = userRepository.findByEmail(email);
    if (userOpt.isPresent()) {
      issueToken(userOpt.get(), ipBucket);
    } else {
      log.info("event=password_reset_requested_unknown_email emailHash={}", emailHash);
    }

    padToFloor(start);
  }

  /**
   * Issues a fresh token for the user, superseding (marking used) any outstanding active tokens so
   * only one token is live per user at a time. Returns the RAW token value — callers decide the
   * delivery channel (dev log or admin endpoint). Always invoked from within a caller-owned
   * transaction (internal call — a {@code @Transactional} annotation here would be bypassed).
   */
  private String issueToken(User user, String ipBucket) {
    Instant now = Instant.now();
    List<PasswordResetToken> active = tokenRepository.findActiveByUserId(user.getId());
    for (PasswordResetToken t : active) {
      t.setUsedAt(now);
    }
    tokenRepository.saveAll(active);

    // 256 random bits, hex-encoded — same opaque-token generator as refresh tokens.
    String rawToken = tokenService.generateRefreshToken();
    PasswordResetToken token =
        PasswordResetToken.builder()
            .userId(user.getId())
            .tokenHash(sha256Hex(rawToken))
            .issuedAt(now)
            .expiresAt(now.plus(TOKEN_TTL))
            .requestedIpBucket(ipBucket)
            .build();
    tokenRepository.save(token);

    log.info("event=password_reset_token_created userId={}", user.getId());
    if (logTokenOnCreate) {
      // Dev-mode helper (ADR-0013): gated by app.password-reset.log-token-on-create, which is
      // false by default and in prod. The marker makes accidental shipping greppable.
      log.info("event=password_reset_token_dev marker=reset-token-dev token={}", rawToken);
    }
    return rawToken;
  }

  /**
   * Consumes a reset token: verifies it, enforces the AUTH-04 strength policy on the new password,
   * updates the user's bcrypt hash, and force-logs-out every session by revoking all refresh tokens
   * (AC-5..AC-10).
   */
  @Transactional
  public void resetPassword(String rawToken, String newPassword, String rawIp) {
    rateLimiter.recordResetAttempt(rawIp);

    String hash = sha256Hex(rawToken);
    PasswordResetToken token =
        tokenRepository.findByTokenHash(hash).orElseThrow(InvalidResetTokenException::new);

    User user =
        userRepository
            .findById(token.getUserId())
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    // Strength check before consuming the token, so a weak password does not burn it (AC-10).
    passwordStrengthValidator.validate(
        newPassword, List.of(user.getEmail(), user.getDisplayName()));

    Instant now = Instant.now();
    int consumed = tokenRepository.markUsedIfActive(hash, now);
    if (consumed == 0) {
      // Already used or expired — one indistinguishable error code for both (AC-8, AC-9).
      throw new InvalidResetTokenException();
    }

    user.setPasswordHash(passwordEncoder.encode(newPassword));
    userRepository.save(user);

    int revoked = refreshTokenRepository.revokeAllForUser(user.getId(), now);
    log.info(
        "event=password_reset_success userId={} refreshTokensRevoked={}", user.getId(), revoked);
  }

  /**
   * Admin retrieval (AC-3, AC-4): rotates and returns the reset token for the given email. Locked
   * to ROLE_ADMIN at the service layer (belt) in addition to the controller annotation (braces).
   *
   * @throws EntityNotFoundException when the email is unknown or has no active token — mapped to
   *     404 by the global handler
   */
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public AdminResetTokenResponse rotateAndGetTokenForEmail(String email) {
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new EntityNotFoundException("No active reset token"));

    List<PasswordResetToken> active =
        tokenRepository.findActiveUnexpiredByUserId(user.getId(), Instant.now());
    if (active.isEmpty()) {
      throw new EntityNotFoundException("No active reset token");
    }

    log.info("event=password_reset_token_admin_retrieval userId={}", user.getId());
    // Rotate: invalidate what exists, mint fresh, return the fresh value (see class javadoc).
    String rawToken = issueToken(user, null);
    PasswordResetToken fresh = tokenRepository.findByTokenHash(sha256Hex(rawToken)).orElseThrow();
    return new AdminResetTokenResponse(rawToken, fresh.getExpiresAt());
  }

  /** Sleeps until at least {@link #FORGOT_FLOOR_MILLIS} have elapsed since {@code startNanos}. */
  private static void padToFloor(long startNanos) {
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    long remaining = FORGOT_FLOOR_MILLIS - elapsedMillis;
    if (remaining > 0) {
      try {
        // Acceptable on the virtual-thread executor (ADR note in the spec); a platform thread
        // would be parked, not burned.
        Thread.sleep(remaining);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  /** Computes a SHA-256 hex digest of the given string. */
  static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
