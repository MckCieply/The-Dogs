package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@Transactional(readOnly = true)
public class AuthService {

  private final TokenService tokenService;
  private final RefreshTokenService refreshTokenService;
  private final UserRepository userRepository;
  private final LoginRateLimiter rateLimiter;
  private final PasswordEncoder passwordEncoder;

  /**
   * Pre-computed BCrypt hash of a dummy password. Used in the "user not found" branch so that the
   * bcrypt comparison is always performed, preventing timing-based user enumeration. Computed once
   * at bean initialization, not per-request.
   */
  private final String dummyHash;

  public AuthService(
      TokenService tokenService,
      RefreshTokenService refreshTokenService,
      UserRepository userRepository,
      LoginRateLimiter rateLimiter,
      PasswordEncoder passwordEncoder) {
    this.tokenService = tokenService;
    this.refreshTokenService = refreshTokenService;
    this.userRepository = userRepository;
    this.rateLimiter = rateLimiter;
    this.passwordEncoder = passwordEncoder;
    // Encode once at startup; BCrypt cost 12 makes this ~300 ms — acceptable at init, not per-call
    this.dummyHash = passwordEncoder.encode("dummy-password-for-timing-auth02");
  }

  public record LoginResult(LoginResponse loginResponse, String refreshToken) {}

  @Transactional
  public LoginResult login(LoginRequest request, HttpServletRequest httpRequest) {
    // Step 1: extract IP for rate limiting and audit
    String rawIp = IpAddressExtractor.extract(httpRequest);

    // Step 2: check rate limit — throws TooManyLoginAttemptsException if over limit
    rateLimiter.checkLimit(rawIp);

    String maskedIp = maskIp(rawIp);

    // Step 3: look up user by email
    Optional<User> userOpt = userRepository.findByEmail(request.email());

    if (userOpt.isEmpty()) {
      // Step 4: unknown email — perform dummy bcrypt comparison to equalise timing with the
      // known-user path (prevents timing-based user enumeration)
      passwordEncoder.matches(request.password(), dummyHash);

      log.info(
          "event=login_failure reason=unknown_email emailHash={} ip={}",
          sha256Hex(request.email().toLowerCase(Locale.ROOT)),
          maskedIp);

      rateLimiter.recordFailure(rawIp);
      throw new BadCredentialsException("bad_credentials");
    }

    User user = userOpt.get();

    // Step 5: account enabled check
    if (!user.isEnabled()) {
      log.info(
          "event=login_failure reason=account_disabled emailHash={} ip={}",
          sha256Hex(request.email().toLowerCase(Locale.ROOT)),
          maskedIp);
      throw new AccountDisabledException();
    }

    // Step 6: verify password
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      log.info(
          "event=login_failure reason=bad_password emailHash={} ip={}",
          sha256Hex(request.email().toLowerCase(Locale.ROOT)),
          maskedIp);
      rateLimiter.recordFailure(rawIp);
      throw new BadCredentialsException("bad_credentials");
    }

    // Step 7: success — generate tokens and persist refresh token row
    log.info("event=login_success userId={} ip={}", user.getId(), maskedIp);

    String accessToken = tokenService.generateAccessToken(user);
    String refreshTokenValue = tokenService.generateRefreshToken();

    // Persist the refresh token to the database (AUTH-03 replaces in-memory RefreshTokenStore)
    String tokenHash = sha256Hex(refreshTokenValue);
    String userAgent = httpRequest.getHeader("User-Agent");
    String ipBucket = maskIp(rawIp);
    Instant expiresAt = Instant.now().plusSeconds(tokenService.getRefreshTokenTtlSeconds());

    refreshTokenService.issue(
        user.getId(),
        expiresAt,
        UUID.randomUUID(), // new family on every login
        null, // no parent — first token in family
        userAgent,
        ipBucket,
        tokenHash);

    return new LoginResult(
        LoginResponse.bearer(accessToken, tokenService.getAccessTokenTtlSeconds()),
        refreshTokenValue);
  }

  // Deliberately NOT @Transactional: rotateToken commits rotation/revocation in its own
  // transaction and throws RefreshTokenReusedException only after that commit. Wrapping this
  // method in a transaction adds no atomicity (the reads below are independent) and an outer
  // rollback must never be able to undo a theft-detection revocation.
  public RefreshResult refresh(String oldRefreshToken) {
    // Generate new token value before rotation so the new hash can be stored atomically
    String newRefreshToken = tokenService.generateRefreshToken();

    // rotateToken handles: atomic rotation, theft detection, family revocation, and issuance
    RefreshToken newToken = refreshTokenService.rotateToken(oldRefreshToken, newRefreshToken);

    // Look up user for access token generation
    User user =
        userRepository
            .findById(newToken.getUserId())
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    String newAccessToken = tokenService.generateAccessToken(user);
    return new RefreshResult(
        new RefreshResponse(newAccessToken, tokenService.getAccessTokenTtlSeconds()),
        newRefreshToken);
  }

  public record RefreshResult(RefreshResponse refreshResponse, String refreshToken) {}

  /**
   * Masks an IP address for structured audit logs. IPv4: zero out the last octet and append /24.
   * IPv6: keep the first 64 bits (4 groups), zero the rest and append /64. Non-parseable values are
   * returned as-is suffixed with "/masked".
   */
  private static String maskIp(String rawIp) {
    if (rawIp == null || rawIp.isBlank()) {
      return "unknown";
    }
    String ip = rawIp.trim();
    if (ip.contains(":")) {
      // IPv6: keep first 4 groups, replace rest with zeros
      String[] parts = ip.split(":", -1);
      if (parts.length >= 4) {
        return parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + parts[3] + ":0:0:0:0/64";
      }
      return ip + "/masked";
    } else if (ip.contains(".")) {
      // IPv4: zero last octet
      int lastDot = ip.lastIndexOf('.');
      if (lastDot > 0) {
        return ip.substring(0, lastDot) + ".0/24";
      }
    }
    return ip + "/masked";
  }

  /** Computes a SHA-256 hex digest of the given string. */
  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      // Convert bytes to hex manually (no Apache Commons Codec dependency needed)
      StringBuilder sb = new StringBuilder(digest.length * 2);
      for (byte b : digest) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is guaranteed to be present in every JVM per the JCA spec
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }
}
