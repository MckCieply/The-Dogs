package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests for {@link RefreshTokenRepository} custom queries (AUTH-03).
 *
 * <p>Uses a real Testcontainers PostgreSQL database via the {@code tc:} JDBC URL configured in
 * {@code application-test.yml}. No {@code @Transactional} at class level — tests manipulate DB
 * state directly and clean up in {@code @AfterEach}, mirroring the approach in {@link
 * AuthRefreshLogoutIT}.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>{@code findActiveByHash} — active, used, revoked, expired variants
 *   <li>{@code findByTokenHash} — always finds a row regardless of state (theft detection read)
 *   <li>{@code markUsedIfActive} — returns 1 when active, 0 when already used / revoked / expired
 *   <li>{@code findActiveByFamilyId} — returns only non-revoked members of a family
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
class RefreshTokenRepositoryIT {

  private static final String TEST_EMAIL = "repo-it@example.com";
  private static final String TEST_PASSWORD = "repo-it-password";

  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordEncoder passwordEncoder;

  private User testUser;

  @BeforeEach
  void setUp() {
    userRepository.findByEmail(TEST_EMAIL).ifPresent(u -> userRepository.delete(u));

    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    testUser =
        User.builder()
            .email(TEST_EMAIL)
            .passwordHash(passwordEncoder.encode(TEST_PASSWORD))
            .roles(Set.of(trainerRole))
            .build();
    userRepository.save(testUser);
  }

  @AfterEach
  void tearDown() {
    userRepository
        .findByEmail(TEST_EMAIL)
        .ifPresent(
            u -> {
              refreshTokenRepository.deleteAll(
                  refreshTokenRepository.findAll().stream()
                      .filter(t -> t.getUserId().equals(u.getId()))
                      .collect(Collectors.toList()));
              userRepository.delete(u);
            });
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** Builds and persists a fresh, active RefreshToken for {@code testUser}. */
  private RefreshToken saveActiveToken(UUID familyId, String plainValue) {
    RefreshToken token =
        RefreshToken.builder()
            .userId(testUser.getId())
            .familyId(familyId)
            .tokenHash(RefreshTokenService.sha256Hex(plainValue))
            .issuedAt(Instant.now().minusSeconds(10))
            .expiresAt(Instant.now().plusSeconds(604_800))
            .build();
    return refreshTokenRepository.save(token);
  }

  // ---------------------------------------------------------------------------
  // findActiveByHash — returns present when token is active (not revoked/expired/used)
  // ---------------------------------------------------------------------------

  @Test
  void findActiveByHash_returnsToken_whenTokenIsActiveUnusedAndUnexpired() {
    String plainValue = "active-token-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    saveActiveToken(UUID.randomUUID(), plainValue);

    Optional<RefreshToken> result = refreshTokenRepository.findActiveByHash(hash, Instant.now());

    assertThat(result)
        .as("findActiveByHash must return the token when it is active, unused, and not expired")
        .isPresent();
    assertThat(result.get().getTokenHash()).isEqualTo(hash);
  }

  @Test
  void findActiveByHash_returnsEmpty_whenTokenHasUsedAtSet() {
    String plainValue = "used-token-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setUsedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findActiveByHash(hash, Instant.now());

    assertThat(result)
        .as("findActiveByHash must return empty when usedAt is set (token was consumed)")
        .isEmpty();
  }

  @Test
  void findActiveByHash_returnsEmpty_whenTokenIsRevoked() {
    String plainValue = "revoked-token-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setRevokedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findActiveByHash(hash, Instant.now());

    assertThat(result).as("findActiveByHash must return empty when revokedAt is set").isEmpty();
  }

  @Test
  void findActiveByHash_returnsEmpty_whenTokenIsExpired() {
    String plainValue = "expired-token-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setExpiresAt(Instant.now().minusSeconds(60));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findActiveByHash(hash, Instant.now());

    assertThat(result)
        .as("findActiveByHash must return empty when the token's expiresAt is in the past")
        .isEmpty();
  }

  @Test
  void findActiveByHash_returnsEmpty_whenNoRowMatchesHash() {
    String unknownHash = RefreshTokenService.sha256Hex("completely-unknown-plain-value");

    Optional<RefreshToken> result =
        refreshTokenRepository.findActiveByHash(unknownHash, Instant.now());

    assertThat(result)
        .as("findActiveByHash must return empty when no row exists for the given hash")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------
  // findByTokenHash — finds a row by hash regardless of state (used, revoked, expired)
  // ---------------------------------------------------------------------------

  @Test
  void findByTokenHash_returnsToken_whenTokenIsActive() {
    String plainValue = "find-any-active-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    saveActiveToken(UUID.randomUUID(), plainValue);

    Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash(hash);

    assertThat(result).as("findByTokenHash must find an active token").isPresent();
  }

  @Test
  void findByTokenHash_returnsToken_whenTokenHasAlreadyBeenUsed() {
    String plainValue = "find-any-used-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setUsedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash(hash);

    assertThat(result)
        .as(
            "findByTokenHash must still find a token even after it has been used (needed for theft detection)")
        .isPresent();
    assertThat(result.get().getUsedAt())
        .as("The returned token must have usedAt populated")
        .isNotNull();
  }

  @Test
  void findByTokenHash_returnsToken_whenTokenIsRevoked() {
    String plainValue = "find-any-revoked-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setRevokedAt(Instant.now().minusSeconds(10));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash(hash);

    assertThat(result)
        .as("findByTokenHash must still find a token that has been revoked")
        .isPresent();
    assertThat(result.get().getRevokedAt())
        .as("The returned token must have revokedAt populated")
        .isNotNull();
  }

  @Test
  void findByTokenHash_returnsToken_whenTokenIsExpired() {
    String plainValue = "find-any-expired-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setExpiresAt(Instant.now().minusSeconds(60));
    refreshTokenRepository.save(token);

    Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash(hash);

    assertThat(result).as("findByTokenHash must still find a token that has expired").isPresent();
  }

  @Test
  void findByTokenHash_returnsEmpty_whenNoRowExists() {
    String unknownHash = RefreshTokenService.sha256Hex("no-such-token-at-all");

    Optional<RefreshToken> result = refreshTokenRepository.findByTokenHash(unknownHash);

    assertThat(result)
        .as("findByTokenHash must return empty when no row exists for the hash")
        .isEmpty();
  }

  // ---------------------------------------------------------------------------
  // markUsedIfActive — returns 1 when active; returns 0 when already used / revoked / expired
  // ---------------------------------------------------------------------------

  @Test
  @Transactional
  void markUsedIfActive_returnsOne_whenTokenIsActiveUnusedAndUnexpired() {
    String plainValue = "mark-used-active-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    saveActiveToken(UUID.randomUUID(), plainValue);

    int updated = refreshTokenRepository.markUsedIfActive(hash, Instant.now());

    assertThat(updated)
        .as("markUsedIfActive must return 1 when the token is active and wins the race")
        .isEqualTo(1);

    // Confirm the row now has usedAt set
    RefreshToken persisted = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    assertThat(persisted.getUsedAt())
        .as("usedAt must be non-null after markUsedIfActive returns 1")
        .isNotNull();
  }

  @Test
  @Transactional
  void markUsedIfActive_returnsZero_whenTokenHasAlreadyBeenUsed() {
    String plainValue = "mark-used-already-used-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    // Pre-mark as used to simulate a prior rotation having already consumed this token
    token.setUsedAt(Instant.now().minusSeconds(1));
    refreshTokenRepository.save(token);

    int updated = refreshTokenRepository.markUsedIfActive(hash, Instant.now());

    assertThat(updated)
        .as(
            "markUsedIfActive must return 0 when the token was already consumed by a prior rotation")
        .isEqualTo(0);
  }

  @Test
  @Transactional
  void markUsedIfActive_returnsZero_whenTokenIsRevoked() {
    String plainValue = "mark-used-revoked-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setRevokedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(token);

    int updated = refreshTokenRepository.markUsedIfActive(hash, Instant.now());

    assertThat(updated)
        .as("markUsedIfActive must return 0 when the token has been revoked")
        .isEqualTo(0);
  }

  @Test
  @Transactional
  void markUsedIfActive_returnsZero_whenTokenIsExpired() {
    String plainValue = "mark-used-expired-value";
    String hash = RefreshTokenService.sha256Hex(plainValue);
    RefreshToken token = saveActiveToken(UUID.randomUUID(), plainValue);
    token.setExpiresAt(Instant.now().minusSeconds(120));
    refreshTokenRepository.save(token);

    int updated = refreshTokenRepository.markUsedIfActive(hash, Instant.now());

    assertThat(updated).as("markUsedIfActive must return 0 when the token is expired").isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // findActiveByFamilyId — returns active (non-revoked) tokens in a family;
  // excludes revoked and used members
  // ---------------------------------------------------------------------------

  @Test
  void findActiveByFamilyId_returnsAllActiveMembersOfFamily() {
    UUID familyId = UUID.randomUUID();
    saveActiveToken(familyId, "family-member-1");
    saveActiveToken(familyId, "family-member-2");

    List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyId(familyId);

    assertThat(active)
        .as("findActiveByFamilyId must return all non-revoked tokens in the family")
        .hasSize(2);
  }

  @Test
  void findActiveByFamilyId_excludesRevokedFamilyMembers() {
    UUID familyId = UUID.randomUUID();
    RefreshToken revokedMember = saveActiveToken(familyId, "family-revoked-member");
    revokedMember.setRevokedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(revokedMember);

    saveActiveToken(familyId, "family-active-member");

    List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyId(familyId);

    assertThat(active)
        .as("findActiveByFamilyId must exclude tokens that have revokedAt set")
        .hasSize(1);
    assertThat(active.get(0).getRevokedAt())
        .as("The returned token must not have revokedAt set")
        .isNull();
  }

  @Test
  void findActiveByFamilyId_includesUsedButNotRevokedMembers() {
    // A token that has been used (consumed by rotation) but not yet revoked is still
    // considered "active" for family-revocation purposes: revokeFamily must reach it too.
    UUID familyId = UUID.randomUUID();
    RefreshToken usedMember = saveActiveToken(familyId, "family-used-not-revoked");
    usedMember.setUsedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(usedMember);

    List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyId(familyId);

    assertThat(active)
        .as(
            "findActiveByFamilyId must include used-but-not-revoked tokens (revokeFamily must reach them)")
        .hasSize(1);
  }

  @Test
  void findActiveByFamilyId_returnsEmptyList_whenFamilyHasNoActiveMembers() {
    UUID familyId = UUID.randomUUID();
    RefreshToken token = saveActiveToken(familyId, "family-all-revoked");
    token.setRevokedAt(Instant.now().minusSeconds(5));
    refreshTokenRepository.save(token);

    List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyId(familyId);

    assertThat(active)
        .as("findActiveByFamilyId must return an empty list when all family members are revoked")
        .isEmpty();
  }

  @Test
  void findActiveByFamilyId_doesNotReturnTokensFromDifferentFamily() {
    UUID targetFamily = UUID.randomUUID();
    UUID otherFamily = UUID.randomUUID();

    saveActiveToken(targetFamily, "target-family-token");
    saveActiveToken(otherFamily, "other-family-token");

    List<RefreshToken> active = refreshTokenRepository.findActiveByFamilyId(targetFamily);

    assertThat(active)
        .as("findActiveByFamilyId must not return tokens belonging to a different family")
        .hasSize(1);
    assertThat(active.get(0).getFamilyId())
        .as("The returned token must belong to the target family")
        .isEqualTo(targetFamily);
  }
}
