package com.thedogs.modules.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Set;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Testcontainers + MockMvc integration tests for the /api/v1/me/ping smoke endpoint (AC-1 through
 * AC-4 of AUTH-01). The /api/v1/auth/some-public-stub endpoint was removed in AUTH-02.
 *
 * <p>The Testcontainers PostgreSQL instance is started automatically via the TC JDBC URL in
 * application-test.yml. No explicit @Testcontainers / @Container annotation is needed here because
 * the JDBC URL uses the tc: scheme which starts the container on first connection.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PingSmokingIT {

  private static final String TEST_SECRET =
      "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";
  private static final String WRONG_SECRET =
      "wrong-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";

  @Autowired private MockMvc mockMvc;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private TokenService tokenService;

  private User trainerUser;
  private String validAccessToken;

  @BeforeEach
  void setUp() {
    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    trainerUser =
        User.builder()
            .email("ping-test-trainer@example.com")
            .passwordHash(passwordEncoder.encode("irrelevant"))
            .roles(Set.of(trainerRole))
            .build();
    trainerUser = userRepository.save(trainerUser);

    validAccessToken = tokenService.generateAccessToken(trainerUser);
  }

  // ---------------------------------------------------------------------------
  // AC-1: GET /api/v1/me/ping without Authorization header → 401 problem+json
  // ---------------------------------------------------------------------------

  @Test
  void ping_withoutAuthorizationHeader_returns401() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/ping"))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"));
  }

  @Test
  void ping_withoutAuthorizationHeader_responseBodyContainsUnauthorizedCode() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/ping"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("unauthorized"));
  }

  // ---------------------------------------------------------------------------
  // AC-2: GET /api/v1/me/ping with valid JWT for ROLE_TRAINER → 200 with email and roles
  // ---------------------------------------------------------------------------

  @Test
  void ping_withValidTrainerToken_returns200() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + validAccessToken))
        .andExpect(status().isOk());
  }

  @Test
  void ping_withValidTrainerToken_responseBodyContainsEmail() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + validAccessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("ping-test-trainer@example.com"));
  }

  @Test
  void ping_withValidTrainerToken_responseBodyContainsRoleTrainer() throws Exception {
    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + validAccessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles").isArray())
        .andExpect(jsonPath("$.roles[0]").value("ROLE_TRAINER"));
  }

  // ---------------------------------------------------------------------------
  // AC-3: GET /api/v1/me/ping with expired JWT → 401 with token_expired code
  // ---------------------------------------------------------------------------

  @Test
  void ping_withExpiredToken_returns401() throws Exception {
    String expiredToken = buildExpiredToken(trainerUser);

    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + expiredToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void ping_withExpiredToken_responseBodyContainsTokenExpiredCode() throws Exception {
    String expiredToken = buildExpiredToken(trainerUser);

    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + expiredToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[0].code").value("token_expired"));
  }

  // ---------------------------------------------------------------------------
  // AC-4: GET /api/v1/me/ping with JWT signed by a different key → 401 invalid_token
  // ---------------------------------------------------------------------------

  @Test
  void ping_withWrongKeyToken_returns401() throws Exception {
    String wrongKeyToken = buildWrongKeyToken(trainerUser);

    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + wrongKeyToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void ping_withWrongKeyToken_responseBodyContainsInvalidTokenCode() throws Exception {
    String wrongKeyToken = buildWrongKeyToken(trainerUser);

    mockMvc
        .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + wrongKeyToken))
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
        .andExpect(jsonPath("$.errors[0].code").value("invalid_token"));
  }

  // ---------------------------------------------------------------------------
  // Token builders for negative path tests
  // ---------------------------------------------------------------------------

  /**
   * Builds a JWT whose expiration is set one hour in the past. The token is signed with the correct
   * test secret and explicit HS256 (matching the decoder) so the signature is valid — only the
   * expiry claim makes it invalid.
   */
  private String buildExpiredToken(User user) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("roles", List.of("ROLE_TRAINER"))
        .issuedAt(Date.from(past.minus(2, ChronoUnit.HOURS)))
        .expiration(Date.from(past))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }

  /**
   * Builds a valid (non-expired) JWT that is signed with HS256 and a key that does not match the
   * application secret. The signature verification will fail when Spring Security tries to decode
   * it.
   */
  private String buildWrongKeyToken(User user) {
    SecretKey key = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(user.getId().toString())
        .claim("roles", List.of("ROLE_TRAINER"))
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
        .signWith(key, Jwts.SIG.HS256)
        .compact();
  }
}
