package com.thedogs.modules.auth;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Issues and validates HS256 JWT access tokens and opaque refresh tokens. */
@Service
public class TokenService {

  private static final SecureRandom SECURE_RANDOM = new SecureRandom();

  private final SecretKey secretKey;
  private final long accessTokenTtlMinutes;
  private final long refreshTokenTtlDays;

  public TokenService(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
      @Value("${jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
    // Use the raw secret bytes. NimbusJwtDecoder in SecurityConfig is built with this same key
    // and defaults to HS256 verification. We also sign with HS256 explicitly to ensure the
    // algorithm is locked regardless of key length (JJWT 0.12+ auto-selects based on length).
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    this.refreshTokenTtlDays = refreshTokenTtlDays;
  }

  public SecretKey getSecretKey() {
    return secretKey;
  }

  public String generateAccessToken(User user) {
    Instant now = Instant.now();
    List<String> roles = user.getRoles().stream().map(Role::getName).toList();

    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .claim("roles", roles)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtlMinutes, ChronoUnit.MINUTES)))
        .signWith(secretKey, Jwts.SIG.HS256)
        .compact();
  }

  public String generateRefreshToken() {
    byte[] bytes = new byte[32]; // 256 bits
    SECURE_RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  public long getRefreshTokenTtlSeconds() {
    return refreshTokenTtlDays * 24 * 60 * 60;
  }

  public long getAccessTokenTtlSeconds() {
    return accessTokenTtlMinutes * 60;
  }

  public Claims parseToken(String token) {
    return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
  }
}
