package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RegisterRequest;
import com.thedogs.modules.auth.exception.EmailTakenException;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account creation (AUTH-04). Validates password strength server-side (zxcvbn ≥ 3),
 * enforces case-insensitive email uniqueness, applies the first-user-becomes-admin bootstrap, and
 * auto-issues the same token pair as login so the new user is signed in immediately.
 *
 * <p>See docs/specs/auth-register.md.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordEncoder passwordEncoder;
  private final PasswordStrengthValidator passwordStrengthValidator;
  private final RegistrationRateLimiter rateLimiter;
  private final TokenService tokenService;
  private final RefreshTokenService refreshTokenService;
  private final EmailVerificationService emailVerificationService;

  /**
   * Self-reference to the Spring proxy of this bean so the non-transactional {@link #register}
   * wrapper can invoke {@link #registerInTransaction} with {@code @Transactional} applied. Same
   * pattern as {@link RefreshTokenService}.
   */
  @Setter(onMethod_ = {@Autowired, @Lazy})
  private RegistrationService self;

  /**
   * Registers a new account and issues a token pair.
   *
   * <p>Non-transactional wrapper: the rate-limit token is consumed only after the transaction has
   * committed, so a registration that fails at the DB level does not burn the caller's hourly
   * budget.
   *
   * @return the login result (access token response + raw refresh token for the cookie)
   */
  public AuthService.LoginResult register(RegisterRequest request, HttpServletRequest httpRequest) {
    String rawIp = IpAddressExtractor.extract(httpRequest);
    rateLimiter.checkLimit(rawIp);

    // Strength gate runs after bean validation (length/format 400s) and before any DB work.
    // Email and display name are penalty-dictionary inputs so "my.email@example.com1" can't pass.
    passwordStrengthValidator.validate(
        request.password(), List.of(request.email(), request.displayName()));

    RegistrationService proxy = (self != null) ? self : this;
    AuthService.LoginResult result = proxy.registerInTransaction(request, httpRequest, rawIp);

    rateLimiter.recordRegistration(rawIp);
    return result;
  }

  /**
   * Transactional core: advisory lock → duplicate check → first-user role decision → insert →
   * refresh-token issuance. Public only so the Spring proxy can apply {@code @Transactional}; call
   * {@link #register} instead.
   */
  @Transactional
  public AuthService.LoginResult registerInTransaction(
      RegisterRequest request, HttpServletRequest httpRequest, String rawIp) {

    // Serialise registrations so two concurrent first-users cannot both observe count()==0
    // and both receive ROLE_ADMIN (AC-8 race requirement).
    userRepository.acquireRegistrationLock();

    if (userRepository.existsByEmailIgnoreCase(request.email())) {
      log.info(
          "event=registration_failure reason=email_taken emailHash={}",
          sha256Hex(request.email().toLowerCase(java.util.Locale.ROOT)));
      throw new EmailTakenException();
    }

    boolean firstUser = userRepository.count() == 0;
    String roleName = firstUser ? "ROLE_ADMIN" : "ROLE_TRAINER";
    Role role =
        roleRepository
            .findByName(roleName)
            .orElseThrow(() -> new IllegalStateException("Role table not seeded: " + roleName));

    User user =
        User.builder()
            .email(request.email())
            .passwordHash(passwordEncoder.encode(request.password()))
            .displayName(request.displayName())
            .roles(Set.of(role))
            // AUTH-09: self-registered accounts start unverified; login is gated until the
            // verification token (dev log / admin endpoint, ADR-0013 pattern) is consumed.
            .emailVerified(false)
            .build();

    try {
      user = userRepository.saveAndFlush(user);
    } catch (DataIntegrityViolationException e) {
      // Belt-and-braces: the advisory lock serialises register-vs-register, but a directly
      // inserted row (admin SQL, tests) can still race the pre-check.
      throw new EmailTakenException();
    }

    // Same transaction as the insert (MANDATORY propagation): no user without a token, no token
    // without a user.
    emailVerificationService.issueForNewUser(user, rawIp);

    log.info(
        "event=registration_success userId={} role={} displayName={}",
        user.getId(),
        roleName,
        user.getDisplayName());

    // Auto-login: same token pair shape as /auth/login (AC-1)
    String accessToken = tokenService.generateAccessToken(user);
    String refreshTokenValue = tokenService.generateRefreshToken();
    Instant expiresAt = Instant.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds());

    refreshTokenService.issue(
        user.getId(),
        expiresAt,
        UUID.randomUUID(), // new token family, exactly like a fresh login
        null,
        httpRequest.getHeader("User-Agent"),
        null,
        sha256Hex(refreshTokenValue));

    return new AuthService.LoginResult(
        LoginResponse.bearer(accessToken, tokenService.getAccessTokenTtlSeconds(), user),
        refreshTokenValue);
  }

  /** Computes a SHA-256 hex digest of the given string. */
  private static String sha256Hex(String input) {
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
