package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.AdminVerificationTokenResponse;
import com.thedogs.modules.auth.exception.InvalidVerificationTokenException;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Email confirmation without SMTP (AUTH-09): a verification token is minted at registration,
 * retrieved out-of-band (dev log or ROLE_ADMIN endpoint, exactly like ADR-0013's password-reset
 * flow), and consumed by confirm-email. Token values are stored only as SHA-256 hashes; every admin
 * fetch ROTATES the active token, so previous values cannot be replayed.
 *
 * <p>Login is gated on {@code users.email_verified} (see {@link AuthService}); accounts that
 * existed before this feature are grandfathered as verified by the V6 migration.
 */
@Service
@Slf4j
public class EmailVerificationService {

  static final Duration TOKEN_TTL = Duration.ofHours(24);

  /** Constant-time floor for resend-confirmation: both branches pad to this duration. */
  static final long RESEND_FLOOR_MILLIS = 80;

  private final UserRepository userRepository;
  private final EmailVerificationTokenRepository tokenRepository;
  private final EmailVerificationRateLimiter rateLimiter;
  private final TokenService tokenService;
  private final boolean logTokenOnCreate;

  public EmailVerificationService(
      UserRepository userRepository,
      EmailVerificationTokenRepository tokenRepository,
      EmailVerificationRateLimiter rateLimiter,
      TokenService tokenService,
      @Value("${app.email-verification.log-token-on-create:false}") boolean logTokenOnCreate) {
    this.userRepository = userRepository;
    this.tokenRepository = tokenRepository;
    this.rateLimiter = rateLimiter;
    this.tokenService = tokenService;
    this.logTokenOnCreate = logTokenOnCreate;
  }

  /**
   * Mints the initial verification token for a freshly registered user. MANDATORY propagation: this
   * must run inside the registration transaction so a failed registration never leaves an orphan
   * token (and vice versa).
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void issueForNewUser(User user, String ipBucket) {
    issueToken(user, ipBucket);
  }

  /**
   * Re-issues a verification token for the given email if an unverified account exists; always
   * behaves identically to the caller (204, wall-clock padded to {@link #RESEND_FLOOR_MILLIS})
   * whether the email is unknown, already verified, or pending — anti-enumeration, mirroring
   * forgot-password.
   */
  @Transactional
  public void resendConfirmation(String email, String ipBucket) {
    long start = System.nanoTime();

    String emailHash = PasswordResetService.sha256Hex(email.toLowerCase(Locale.ROOT));
    rateLimiter.recordResendAttempt(emailHash);

    Optional<User> userOpt = userRepository.findByEmail(email);
    if (userOpt.isPresent() && !userOpt.get().isEmailVerified()) {
      issueToken(userOpt.get(), ipBucket);
    } else {
      log.info("event=email_verification_resend_noop emailHash={}", emailHash);
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
    List<EmailVerificationToken> active = tokenRepository.findActiveByUserId(user.getId());
    for (EmailVerificationToken t : active) {
      t.setUsedAt(now);
    }
    tokenRepository.saveAll(active);

    // 256 random bits, hex-encoded — same opaque-token generator as refresh/reset tokens.
    String rawToken = tokenService.generateRefreshToken();
    EmailVerificationToken token =
        EmailVerificationToken.builder()
            .userId(user.getId())
            .tokenHash(PasswordResetService.sha256Hex(rawToken))
            .issuedAt(now)
            .expiresAt(now.plus(TOKEN_TTL))
            .requestedIpBucket(ipBucket)
            .build();
    tokenRepository.save(token);

    log.info("event=email_verification_token_created userId={}", user.getId());
    if (logTokenOnCreate) {
      // Dev-mode helper (ADR-0013 pattern): gated by app.email-verification.log-token-on-create,
      // false by default and in prod. The marker makes accidental shipping greppable.
      log.info("event=email_verification_token_dev marker=verify-token-dev token={}", rawToken);
    }
    return rawToken;
  }

  /**
   * Consumes a verification token and marks the account's email as verified. Unknown, used, and
   * expired tokens all produce the same error.
   */
  @Transactional
  public void confirmEmail(String rawToken, String rawIp) {
    rateLimiter.recordConfirmAttempt(rawIp);

    String hash = PasswordResetService.sha256Hex(rawToken);
    EmailVerificationToken token =
        tokenRepository.findByTokenHash(hash).orElseThrow(InvalidVerificationTokenException::new);

    User user =
        userRepository
            .findById(token.getUserId())
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    Instant now = Instant.now();
    int consumed = tokenRepository.markUsedIfActive(hash, now);
    if (consumed == 0) {
      // Already used or expired — one indistinguishable error code for both.
      throw new InvalidVerificationTokenException();
    }

    user.setEmailVerified(true);
    userRepository.save(user);

    log.info("event=email_verification_success userId={}", user.getId());
  }

  /**
   * Admin retrieval: rotates and returns the verification token for the given email. Locked to
   * ROLE_ADMIN at the service layer (belt) in addition to the controller annotation (braces).
   *
   * @throws EntityNotFoundException when the email is unknown, already verified, or has no active
   *     token — mapped to 404 by the global handler
   */
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public AdminVerificationTokenResponse rotateAndGetTokenForEmail(String email) {
    User user =
        userRepository
            .findByEmail(email)
            .orElseThrow(() -> new EntityNotFoundException("No active verification token"));

    if (user.isEmailVerified()) {
      throw new EntityNotFoundException("No active verification token");
    }

    List<EmailVerificationToken> active =
        tokenRepository.findActiveUnexpiredByUserId(user.getId(), Instant.now());
    if (active.isEmpty()) {
      throw new EntityNotFoundException("No active verification token");
    }

    log.info("event=email_verification_token_admin_retrieval userId={}", user.getId());
    // Rotate: invalidate what exists, mint fresh, return the fresh value (see class javadoc).
    String rawToken = issueToken(user, null);
    EmailVerificationToken fresh =
        tokenRepository.findByTokenHash(PasswordResetService.sha256Hex(rawToken)).orElseThrow();
    return new AdminVerificationTokenResponse(rawToken, fresh.getExpiresAt());
  }

  /** Sleeps until at least {@link #RESEND_FLOOR_MILLIS} have elapsed since {@code startNanos}. */
  private static void padToFloor(long startNanos) {
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;
    long remaining = RESEND_FLOOR_MILLIS - elapsedMillis;
    if (remaining > 0) {
      try {
        Thread.sleep(remaining);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
