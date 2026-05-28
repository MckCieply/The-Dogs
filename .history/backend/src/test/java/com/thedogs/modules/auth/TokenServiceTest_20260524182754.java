package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pure unit test for TokenService — no Spring context, no DB, no Docker required. */
class TokenServiceTest {

  private static final String SECRET =
      "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";

  private TokenService tokenService;
  private User testUser;

  @BeforeEach
  void setUp() {
    tokenService = new TokenService(SECRET, 15L, 7L);

    // Build a Role entity manually (no DB needed for unit test)
    Role trainerRole = Role.builder().id((short) 1).name("ROLE_TRAINER").build();

    // Build a User without saving to DB — we only need the id, email and roles
    testUser =
        User.builder()
            .email("trainer@example.com")
            .passwordHash("irrelevant")
<<<<<<< HEAD
            .roles(Set.of(trainerRole))
=======
            .roles(List.of(Role.ROLE_TRAINER))
>>>>>>> ab0225a3e00e32d78d275eb110aaa21b3ea2d6e4
            .build();

    // Set the ID via reflection so we can verify it appears in the token
    try {
      var idField = com.thedogs.common.BaseEntity.class.getDeclaredField("id");
      idField.setAccessible(true);
      idField.set(testUser, UUID.fromString("00000000-0000-0000-0000-000000000001"));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void generateAccessToken_containsSubjectAndRoles() {
    String token = tokenService.generateAccessToken(testUser);

    assertThat(token).isNotBlank();

    Claims claims = tokenService.parseToken(token);
    assertThat(claims.getSubject()).isEqualTo("00000000-0000-0000-0000-000000000001");
    assertThat(claims.get("email", String.class)).isEqualTo("trainer@example.com");

    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);
    assertThat(roles).containsExactly("ROLE_TRAINER");
  }

  @Test
  void generateRefreshToken_isUniquePerCall() {
    String t1 = tokenService.generateRefreshToken();
    String t2 = tokenService.generateRefreshToken();
    assertThat(t1).isNotEqualTo(t2);
  }

  @Test
  void parseToken_withTamperedToken_throwsJwtException() {
    String token = tokenService.generateAccessToken(testUser);
    String tampered = token.substring(0, token.length() - 4) + "XXXX";

    assertThatThrownBy(() -> tokenService.parseToken(tampered)).isInstanceOf(JwtException.class);
  }

  @Test
  void getAccessTokenTtlSeconds_returns15Minutes() {
    assertThat(tokenService.getAccessTokenTtlSeconds()).isEqualTo(900L);
  }
}
