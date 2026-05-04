package com.thedogs.modules.auth;

import com.thedogs.modules.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Issues and validates HS256 JWT access tokens and opaque refresh tokens. */
@Service
public class TokenService {

  private final SecretKey secretKey;
  private final long accessTokenTtlMinutes;
  private final long refreshTokenTtlDays;

  public TokenService(
      @Value("${jwt.secret}") String secret,
      @Value("${jwt.access-token-ttl-minutes}") long accessTokenTtlMinutes,
      @Value("${jwt.refresh-token-ttl-days}") long refreshTokenTtlDays) {
    this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    this.accessTokenTtlMinutes = accessTokenTtlMinutes;
    this.refreshTokenTtlDays = refreshTokenTtlDays;
  }

  public SecretKey getSecretKey() {
    return secretKey;
  }

  public String generateAccessToken(User user) {
    Instant now = Instant.now();
    List<String> roles = user.getRoles().stream().map(Enum::name).toList();

    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("email", user.getEmail())
        .claim("roles", roles)
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(accessTokenTtlMinutes, ChronoUnit.MINUTES)))
        .signWith(secretKey)
        .compact();
  }

  public String generateRefreshToken() {
    return UUID.randomUUID().toString();
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
