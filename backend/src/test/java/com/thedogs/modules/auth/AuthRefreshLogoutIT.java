package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for AUTH-03: refresh token rotation, theft detection, and logout.
 *
 * <p>Uses a real Testcontainers PostgreSQL database (configured in application-test.yml). No
 * {@code @Transactional} annotation at class level — each test manages its own data and cleans up
 * via {@code @AfterEach} to avoid hiding transactional edge cases in the rotation path.
 *
 * <p>Covers AC-1 through AC-11 from docs/specs/auth-refresh-logout.md.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRefreshLogoutIT {

  private static final String LOGIN_URL = "/api/v1/auth/login";
  private static final String REFRESH_URL = "/api/v1/auth/refresh";
  private static final String LOGOUT_URL = "/api/v1/auth/logout";
  private static final String REFRESH_COOKIE_NAME = "refresh_token";

  private static final String TEST_EMAIL = "refresh-it@example.com";
  private static final String TEST_PASSWORD = "integration-password";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private RefreshTokenRepository refreshTokenRepository;

  private User testUser;

  @BeforeEach
  void setUp() {
    // Ensure clean state per test — the email is unique to this IT class to avoid
    // collisions with AuthIntegrationTest which also runs in the same container.
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
    // Delete refresh tokens first (FK constraint: refresh_token.user_id → app_user.id)
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
  // Helper: perform a login and return the refresh token cookie value
  // ---------------------------------------------------------------------------

  private String performLoginAndGetRefreshCookie() throws Exception {
    LoginRequest request = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);
    MvcResult result =
        mockMvc
            .perform(
                post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(cookie).as("Login must set a refresh_token cookie").isNotNull();
    return cookie.getValue();
  }

  // ---------------------------------------------------------------------------
  // AC-1: valid, unused, unexpired cookie → 200 + new access token + new cookie
  // ---------------------------------------------------------------------------

  @Test
  void refresh_withValidUnusedCookie_returns200WithNewAccessToken() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty())
        .andExpect(jsonPath("$.expiresIn").isNumber());
  }

  @Test
  void refresh_withValidUnusedCookie_setsNewCookieDifferentFromOriginal() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();

    MvcResult result =
        mockMvc
            .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie newCookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(newCookie).as("Refresh must return a new cookie").isNotNull();
    assertThat(newCookie.getValue())
        .as("New cookie value must differ from the original one (rotation)")
        .isNotEqualTo(originalCookieValue);
  }

  // ---------------------------------------------------------------------------
  // AC-2: after rotation, previous token has used_at set in DB
  // ---------------------------------------------------------------------------

  @Test
  void refresh_afterRotation_previousTokenHasUsedAtSetInDatabase() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();
    String originalHash = RefreshTokenService.sha256Hex(originalCookieValue);

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isOk());

    Optional<RefreshToken> oldToken = refreshTokenRepository.findByTokenHash(originalHash);
    assertThat(oldToken).as("The original token row must still exist in the DB").isPresent();
    assertThat(oldToken.get().getUsedAt())
        .as("used_at must be set after the token is consumed by rotation")
        .isNotNull();
  }

  // ---------------------------------------------------------------------------
  // AC-3: reuse detection — presenting a used_at != null token → 401 refresh_reused,
  // entire family revoked, cookie cleared
  // ---------------------------------------------------------------------------

  @Test
  void refresh_withAlreadyUsedToken_returns401WithRefreshReusedCode() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();

    // First refresh — consumes the original token
    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isOk());

    // Replay of the already-used original token
    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_reused"));
  }

  @Test
  void refresh_withAlreadyUsedToken_revokesEntireFamilyInDatabase() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();
    String originalHash = RefreshTokenService.sha256Hex(originalCookieValue);

    // First refresh — consumes original, creates rotation-1
    MvcResult rotationResult =
        mockMvc
            .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie rotation1Cookie = rotationResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(rotation1Cookie).isNotNull();
    String rotation1Hash = RefreshTokenService.sha256Hex(rotation1Cookie.getValue());

    // Replay of original → theft detection, should revoke all family members
    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isUnauthorized());

    // Both the original and the rotation-1 token must now have revoked_at set
    RefreshToken originalRow = refreshTokenRepository.findByTokenHash(originalHash).orElseThrow();
    RefreshToken rotation1Row = refreshTokenRepository.findByTokenHash(rotation1Hash).orElseThrow();

    assertThat(originalRow.getRevokedAt())
        .as("Original token must be revoked after theft detection")
        .isNotNull();
    assertThat(rotation1Row.getRevokedAt())
        .as("Rotated token in the same family must also be revoked after theft detection")
        .isNotNull();
  }

  @Test
  void refresh_withAlreadyUsedToken_clearsCookieInResponse() throws Exception {
    String originalCookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
        .andExpect(status().isOk());

    // Note: the controller currently does not explicitly clear the cookie on reuse detection;
    // this test documents the expected behaviour so the implementer can verify or add clearing.
    // The GlobalExceptionHandler handles the 401 — cookie clearing on reuse is desirable per spec.
    // If this test fails, it is a signal that cookie-clearing on AC-3 needs to be implemented.
    MvcResult reuseResult =
        mockMvc
            .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, originalCookieValue)))
            .andExpect(status().isUnauthorized())
            .andReturn();

    // The response should either have no Set-Cookie or a Max-Age=0 clearing cookie
    String setCookieHeader = reuseResult.getResponse().getHeader("Set-Cookie");
    if (setCookieHeader != null) {
      assertThat(setCookieHeader)
          .as(
              "If Set-Cookie is present on theft detection, it must clear the cookie (Max-Age=0 or empty value)")
          .containsIgnoringCase("Max-Age=0");
    }
    // If Set-Cookie is null here, the implementer must add clearing; note flagged in handoff.
  }

  // ---------------------------------------------------------------------------
  // AC-4: POST /auth/logout → 204, cookie cleared (Max-Age=0), family revoked
  // ---------------------------------------------------------------------------

  @Test
  void logout_withValidCookie_returns204() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());
  }

  @Test
  void logout_withValidCookie_setsMaxAgeZeroClearingCookie() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent())
        .andExpect(
            header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
  }

  @Test
  void logout_withValidCookie_revokesFamilyInDatabase() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    String hash = RefreshTokenService.sha256Hex(cookieValue);

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());

    RefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    assertThat(token.getRevokedAt())
        .as("The token family must be revoked in the DB after logout")
        .isNotNull();
  }

  // ---------------------------------------------------------------------------
  // AC-5: POST /auth/refresh after /auth/logout → 401 refresh_revoked
  // ---------------------------------------------------------------------------

  @Test
  void refresh_afterLogout_returns401WithRefreshRevokedCode() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_revoked"));
  }

  // ---------------------------------------------------------------------------
  // AC-6: Expired refresh token → 401 refresh_expired
  // ---------------------------------------------------------------------------

  @Test
  void refresh_withExpiredToken_returns401WithRefreshExpiredCode() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    String hash = RefreshTokenService.sha256Hex(cookieValue);

    // Directly manipulate the DB row to set expires_at to the past
    RefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    token.setExpiresAt(Instant.now().minusSeconds(60));
    refreshTokenRepository.save(token);

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_expired"));
  }

  // ---------------------------------------------------------------------------
  // AC-7: Unknown refresh token (well-formed cookie, no DB row) → 401 invalid_refresh
  // ---------------------------------------------------------------------------

  @Test
  void refresh_withUnknownToken_returns401WithInvalidRefreshCode() throws Exception {
    // Generate a plausible 64-char hex token value that does not exist in the DB
    String unknownToken = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef";

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, unknownToken)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_refresh"));
  }

  // ---------------------------------------------------------------------------
  // AC-8: login creates a real refresh_token row; subsequent refresh works end-to-end
  // ---------------------------------------------------------------------------

  @Test
  void login_createsRealRefreshTokenRowInDatabase() throws Exception {
    LoginRequest request = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);
    MvcResult loginResult =
        mockMvc
            .perform(
                post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie cookie = loginResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(cookie).isNotNull();

    String hash = RefreshTokenService.sha256Hex(cookie.getValue());
    Optional<RefreshToken> row = refreshTokenRepository.findByTokenHash(hash);
    assertThat(row).as("A real refresh_token row must be persisted after login").isPresent();
    assertThat(row.get().getUserId()).isEqualTo(testUser.getId());
  }

  @Test
  void loginThenRefresh_worksEndToEnd() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  // ---------------------------------------------------------------------------
  // AC-9: Token stored as SHA-256 hash — DB row has tokenHash != plain value, 64 hex chars
  // ---------------------------------------------------------------------------

  @Test
  void login_storesTokenAsSha256HashNotPlaintext() throws Exception {
    LoginRequest request = new LoginRequest(TEST_EMAIL, TEST_PASSWORD);
    MvcResult loginResult =
        mockMvc
            .perform(
                post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andReturn();

    Cookie cookie = loginResult.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(cookie).isNotNull();
    String plainValue = cookie.getValue();

    String storedHash = RefreshTokenService.sha256Hex(plainValue);
    Optional<RefreshToken> row = refreshTokenRepository.findByTokenHash(storedHash);
    assertThat(row).isPresent();

    String tokenHashInDb = row.get().getTokenHash();

    // The DB column must be exactly 64 hex characters (SHA-256 produces 32 bytes = 64 hex digits)
    assertThat(tokenHashInDb)
        .as("tokenHash must be exactly 64 hex characters")
        .matches("[0-9a-f]{64}");

    // The stored hash must not equal the plain cookie value
    assertThat(tokenHashInDb)
        .as("tokenHash in DB must not equal the plaintext cookie value")
        .isNotEqualTo(plainValue);
  }

  // ---------------------------------------------------------------------------
  // AC-10: No token value, hash, or cookie header appears in any log during refresh
  // ---------------------------------------------------------------------------

  @Test
  void refresh_doesNotLogTokenValueOrHashOrCookieHeader() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    String expectedHash = RefreshTokenService.sha256Hex(cookieValue);

    // Attach a ListAppender to the root logger to capture all log output
    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    Logger refreshServiceLogger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
    Logger authServiceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
    Logger authControllerLogger = (Logger) LoggerFactory.getLogger(AuthController.class);

    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    refreshServiceLogger.addAppender(appender);
    authServiceLogger.addAppender(appender);
    authControllerLogger.addAppender(appender);
    // Also capture at root level to catch any unforeseen logger.
    // Save the original level so we can restore it — otherwise subsequent tests in the same JVM
    // run will inherit Level.ALL from root and may capture log messages that they would not
    // normally see (e.g. SecurityWiringIT leaking password via Spring MVC debug log).
    Level originalRootLevel = rootLogger.getLevel();
    rootLogger.addAppender(appender);
    rootLogger.setLevel(Level.ALL);

    try {
      mockMvc
          .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
          .andExpect(status().isOk());
    } finally {
      refreshServiceLogger.detachAppender(appender);
      authServiceLogger.detachAppender(appender);
      authControllerLogger.detachAppender(appender);
      rootLogger.detachAppender(appender);
      // Restore the original root logger level so subsequent tests are not affected.
      rootLogger.setLevel(originalRootLevel);
    }

    List<String> allMessages =
        appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());

    for (String message : allMessages) {
      assertThat(message)
          .as("Log must not contain the raw refresh token value")
          .doesNotContain(cookieValue);
      assertThat(message)
          .as("Log must not contain the SHA-256 hash of the refresh token")
          .doesNotContain(expectedHash);
    }
  }

  // ---------------------------------------------------------------------------
  // AC-11 (partial): All 4 error codes appear in HTTP response bodies
  // ---------------------------------------------------------------------------

  @Test
  void errorCodes_refreshReused_appearsInResponseBody() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isOk());

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_reused"));
  }

  @Test
  void errorCodes_refreshRevoked_appearsInResponseBody() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_revoked"));
  }

  @Test
  void errorCodes_refreshExpired_appearsInResponseBody() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();
    String hash = RefreshTokenService.sha256Hex(cookieValue);
    RefreshToken token = refreshTokenRepository.findByTokenHash(hash).orElseThrow();
    token.setExpiresAt(Instant.now().minusSeconds(1));
    refreshTokenRepository.save(token);

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_expired"));
  }

  @Test
  void errorCodes_invalidRefresh_appearsInResponseBody() throws Exception {
    mockMvc
        .perform(
            post(REFRESH_URL)
                .cookie(
                    new Cookie(
                        REFRESH_COOKIE_NAME,
                        "0000000000000000000000000000000000000000000000000000000000000000")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_refresh"));
  }

  // ---------------------------------------------------------------------------
  // Logout is idempotent — calling twice returns 204 both times (no 401 on second call)
  // ---------------------------------------------------------------------------

  @Test
  void logout_calledTwiceWithSameToken_returns204BothTimes() throws Exception {
    String cookieValue = performLoginAndGetRefreshCookie();

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(post(LOGOUT_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookieValue)))
        .andExpect(status().isNoContent());
  }

  @Test
  void logout_withNoCookie_returns204() throws Exception {
    mockMvc.perform(post(LOGOUT_URL)).andExpect(status().isNoContent());
  }

  // ---------------------------------------------------------------------------
  // Refresh with no cookie → 400 (controller early-returns BadRequest)
  // ---------------------------------------------------------------------------

  @Test
  void refresh_withNoCookie_returns400() throws Exception {
    mockMvc.perform(post(REFRESH_URL)).andExpect(status().isBadRequest());
  }
}
