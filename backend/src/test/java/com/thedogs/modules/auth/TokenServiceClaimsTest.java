package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import io.jsonwebtoken.Claims;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for TokenService claim edge cases not covered by TokenServiceTest.
 *
 * <p>Specifically covers:
 *
 * <ul>
 *   <li>The {@code sub} claim is a valid UUID string matching the user's id
 *   <li>The {@code email} claim is present and correct
 *   <li>The {@code roles} claim is a {@link List}
 *   <li>The {@code exp} claim is set to approximately 15 minutes in the future (within a 2-second
 *       window to account for test execution time)
 * </ul>
 *
 * <p>No Spring context, no DB, no Docker required.
 */
class TokenServiceClaimsTest {

  private static final String SECRET =
      "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";

  private static final long ACCESS_TOKEN_TTL_MINUTES = 15L;
  private static final long REFRESH_TOKEN_TTL_DAYS = 7L;

  private TokenService tokenService;
  private User testUser;
  private UUID testUserId;

  @BeforeEach
  void setUp() {
    tokenService = new TokenService(SECRET, ACCESS_TOKEN_TTL_MINUTES, REFRESH_TOKEN_TTL_DAYS);

    testUserId = UUID.fromString("00000000-0000-0000-0000-000000000002");

    Role trainerRole = Role.builder().id((short) 1).name("ROLE_TRAINER").build();

    testUser =
        User.builder()
            .email("claims-test-trainer@example.com")
            .passwordHash("irrelevant")
            .roles(Set.of(trainerRole))
            .build();

    try {
      var idField = com.thedogs.common.BaseEntity.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(testUser, testUserId);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  // ---------------------------------------------------------------------------
  // sub claim is a UUID string matching user id
  // ---------------------------------------------------------------------------

  @Test
  void generateAccessToken_subClaimIsUuidStringMatchingUserId() {
    Claims claims = tokenService.parseToken(tokenService.generateAccessToken(testUser));

    String sub = claims.getSubject();

    // Must parse as a UUID without throwing — proves the value is a well-formed UUID string
    UUID parsedSub = UUID.fromString(sub);
    assertThat(parsedSub).as("sub claim must be the UUID of the user").isEqualTo(testUserId);
  }

  // ---------------------------------------------------------------------------
  // email claim is present
  // ---------------------------------------------------------------------------

  @Test
  void generateAccessToken_emailClaimMatchesUserEmail() {
    Claims claims = tokenService.parseToken(tokenService.generateAccessToken(testUser));

    assertThat(claims.get("email", String.class))
        .as("email claim must match the user's email address")
        .isEqualTo("claims-test-trainer@example.com");
  }

  // ---------------------------------------------------------------------------
  // roles claim is a List (not a scalar)
  // ---------------------------------------------------------------------------

  @Test
  void generateAccessToken_rolesClaimIsAList() {
    Claims claims = tokenService.parseToken(tokenService.generateAccessToken(testUser));

    Object rawRoles = claims.get("roles");

    assertThat(rawRoles)
        .as("roles claim must be a List, not a scalar value")
        .isInstanceOf(List.class);
  }

  // ---------------------------------------------------------------------------
  // exp claim is set to ~15 minutes in the future
  // ---------------------------------------------------------------------------

  @Test
  void generateAccessToken_expClaimIsApproximately15MinutesInFuture() {
    Instant beforeGeneration = Instant.now();

    Claims claims = tokenService.parseToken(tokenService.generateAccessToken(testUser));

    Instant afterGeneration = Instant.now();

    Instant exp = claims.getExpiration().toInstant();

    // Lower bound: exp must be at least (now + 15 min - 2 s) at the moment of generation
    Instant expectedLower = beforeGeneration.plusSeconds(ACCESS_TOKEN_TTL_MINUTES * 60 - 2);
    // Upper bound: exp must be no more than (now + 15 min + 2 s) at the moment we checked
    Instant expectedUpper = afterGeneration.plusSeconds(ACCESS_TOKEN_TTL_MINUTES * 60 + 2);

    assertThat(exp)
        .as("exp claim must be approximately 15 minutes from token generation time")
        .isAfter(expectedLower)
        .isBefore(expectedUpper);
  }
}
