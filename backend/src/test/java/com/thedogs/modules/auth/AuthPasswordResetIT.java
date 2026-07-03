package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * Integration tests for AUTH-05: password reset (admin-token flow, no SMTP).
 *
 * <p>Covers AC-1 through AC-12 from docs/specs/auth-password-reset.md. Uses the dev-mode token log
 * helper (enabled in the test profile) to capture raw token values, which simultaneously proves
 * AC-12's "on" direction.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthPasswordResetIT {

  private static final String FORGOT_URL = "/api/v1/auth/forgot-password";
  private static final String RESET_URL = "/api/v1/auth/reset-password";
  private static final String LOGIN_URL = "/api/v1/auth/login";
  private static final String REFRESH_URL = "/api/v1/auth/refresh";
  private static final String ADMIN_TOKENS_URL = "/api/v1/admin/password-reset-tokens";

  private static final String TEST_EMAIL = "reset-it@example.com";
  private static final String OLD_PASSWORD = "original-Secret#67!pass";
  private static final String NEW_PASSWORD = "brand-new!Secret#42*word";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordResetTokenRepository resetTokenRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private PasswordResetRateLimiter rateLimiter;
  @Autowired private LoginRateLimiter loginRateLimiter;

  private User testUser;

  @BeforeEach
  void setUp() {
    rateLimiter.clearAll();
    loginRateLimiter.clearAll();
    cleanup();

    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    testUser =
        User.builder()
            .email(TEST_EMAIL)
            .passwordHash(passwordEncoder.encode(OLD_PASSWORD))
            .roles(Set.of(trainerRole))
            .build();
    userRepository.save(testUser);
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  private void cleanup() {
    userRepository
        .findByEmail(TEST_EMAIL)
        .ifPresent(
            u -> {
              resetTokenRepository.deleteAll(
                  resetTokenRepository.findAll().stream()
                      .filter(t -> t.getUserId().equals(u.getId()))
                      .collect(Collectors.toList()));
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

  private void requestForgotPassword(String email) throws Exception {
    mockMvc
        .perform(
            post(FORGOT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isNoContent());
  }

  /**
   * Requests a reset token and captures the raw value from the dev-mode log helper (enabled in the
   * test profile — this is also the AC-12 "on" assertion).
   */
  private String forgotPasswordAndCaptureToken() throws Exception {
    Logger serviceLogger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      requestForgotPassword(TEST_EMAIL);
    } finally {
      serviceLogger.detachAppender(appender);
    }

    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains("marker=reset-token-dev"))
        .map(m -> m.replaceAll(".*token=([0-9a-f]{64}).*", "$1"))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Dev-mode token log line not found — AC-12 helper must be on in test profile"));
  }

  private String loginAndGetRefreshCookie(String password) throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(LOGIN_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(new LoginRequest(TEST_EMAIL, password))))
            .andExpect(status().isOk())
            .andReturn();
    Cookie cookie = result.getResponse().getCookie("refresh_token");
    assertThat(cookie).isNotNull();
    return cookie.getValue();
  }

  private String resetBody(String token, String newPassword) {
    return "{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}";
  }

  // ---------------------------------------------------------------------------
  // AC-1: forgot-password with known email → 204 + one active token row, 1h expiry
  // ---------------------------------------------------------------------------

  @Test
  void forgotPassword_withKnownEmail_returns204AndInsertsTokenRow() throws Exception {
    Instant before = Instant.now();
    requestForgotPassword(TEST_EMAIL);

    List<PasswordResetToken> active =
        resetTokenRepository.findActiveByUserId(testUser.getId());
    assertThat(active).hasSize(1);
    PasswordResetToken token = active.get(0);
    assertThat(token.getUsedAt()).isNull();
    assertThat(token.getExpiresAt())
        .as("Token must expire ~1 hour after issuance")
        .isAfter(before.plusSeconds(3500))
        .isBefore(before.plusSeconds(3700));
  }

  @Test
  void forgotPassword_secondRequest_supersedesFirstToken() throws Exception {
    String first = forgotPasswordAndCaptureToken();
    String second = forgotPasswordAndCaptureToken();
    assertThat(second).isNotEqualTo(first);

    assertThat(resetTokenRepository.findActiveByUserId(testUser.getId()))
        .as("Only one active token per user at a time")
        .hasSize(1);

    // The superseded token must no longer work.
    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(first, NEW_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_reset_token"));
  }

  // ---------------------------------------------------------------------------
  // AC-2: unknown email → 204, no row, comparable timing (floor asserted)
  // ---------------------------------------------------------------------------

  @Test
  void forgotPassword_withUnknownEmail_returns204WithoutInsertingRow() throws Exception {
    long rowsBefore = resetTokenRepository.count();
    long start = System.nanoTime();
    requestForgotPassword("nobody-here@example.com");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertThat(resetTokenRepository.count()).isEqualTo(rowsBefore);
    assertThat(elapsedMs)
        .as("Anti-enumeration constant-time floor (>=80ms) must apply to the unknown-email path")
        .isGreaterThanOrEqualTo(80);
  }

  @Test
  void forgotPassword_knownEmailPath_alsoPadsToConstantTimeFloor() throws Exception {
    long start = System.nanoTime();
    requestForgotPassword(TEST_EMAIL);
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;
    assertThat(elapsedMs).isGreaterThanOrEqualTo(80);
  }

  // ---------------------------------------------------------------------------
  // AC-3 / AC-4: admin retrieval — ADMIN 200 (rotated token), TRAINER 403, none 404
  // ---------------------------------------------------------------------------

  @Test
  void adminGetToken_asAdmin_returnsFreshTokenThatWorks() throws Exception {
    forgotPasswordAndCaptureToken();

    MvcResult result =
        mockMvc
            .perform(
                get(ADMIN_TOKENS_URL)
                    .param("email", TEST_EMAIL)
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.user("admin")
                            .roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").isNotEmpty())
            .andExpect(jsonPath("$.expiresAt").isNotEmpty())
            .andReturn();

    String adminToken =
        objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();

    // The freshly rotated token must be usable for an actual reset.
    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(adminToken, NEW_PASSWORD)))
        .andExpect(status().isNoContent());
  }

  @Test
  void adminGetToken_rotationInvalidatesPreviousToken() throws Exception {
    String original = forgotPasswordAndCaptureToken();

    mockMvc
        .perform(
            get(ADMIN_TOKENS_URL)
                .param("email", TEST_EMAIL)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(original, NEW_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_reset_token"));
  }

  @Test
  void adminGetToken_asTrainer_returns403Forbidden() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_TOKENS_URL)
                .param("email", TEST_EMAIL)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("trainer")
                        .roles("TRAINER")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.errors[0].code").value("forbidden"));
  }

  @Test
  void adminGetToken_whenNoActiveToken_returns404() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_TOKENS_URL)
                .param("email", TEST_EMAIL)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isNotFound());
  }

  // ---------------------------------------------------------------------------
  // AC-5 / AC-6 / AC-7: successful reset updates password, revokes sessions
  // ---------------------------------------------------------------------------

  @Test
  void resetPassword_withValidToken_updatesPasswordMarksUsedAndRevokesSessions() throws Exception {
    String refreshCookie = loginAndGetRefreshCookie(OLD_PASSWORD);
    String token = forgotPasswordAndCaptureToken();

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, NEW_PASSWORD)))
        .andExpect(status().isNoContent());

    // Token marked used
    Optional<PasswordResetToken> row =
        resetTokenRepository.findByTokenHash(PasswordResetService.sha256Hex(token));
    assertThat(row).isPresent();
    assertThat(row.get().getUsedAt()).isNotNull();

    // AC-6: new password logs in, old is rejected
    mockMvc
        .perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new LoginRequest(TEST_EMAIL, NEW_PASSWORD))))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(new LoginRequest(TEST_EMAIL, OLD_PASSWORD))))
        .andExpect(status().isUnauthorized());

    // AC-7: the pre-reset refresh token family is revoked
    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie("refresh_token", refreshCookie)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("refresh_revoked"));
  }

  // ---------------------------------------------------------------------------
  // AC-8 / AC-9: used and expired tokens → 400 invalid_reset_token
  // ---------------------------------------------------------------------------

  @Test
  void resetPassword_withUsedToken_returns400InvalidResetToken() throws Exception {
    String token = forgotPasswordAndCaptureToken();

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, NEW_PASSWORD)))
        .andExpect(status().isNoContent());

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, "another-Fresh#88!secret")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_reset_token"));
  }

  @Test
  void resetPassword_withExpiredToken_returns400InvalidResetToken() throws Exception {
    String token = forgotPasswordAndCaptureToken();

    PasswordResetToken row =
        resetTokenRepository.findByTokenHash(PasswordResetService.sha256Hex(token)).orElseThrow();
    row.setExpiresAt(Instant.now().minusSeconds(60));
    resetTokenRepository.save(row);

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, NEW_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_reset_token"));
  }

  @Test
  void resetPassword_withUnknownToken_returns400InvalidResetToken() throws Exception {
    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    resetBody(
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        NEW_PASSWORD)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_reset_token"));
  }

  // ---------------------------------------------------------------------------
  // AC-10: weak new password → 400 password_too_weak, token NOT consumed
  // ---------------------------------------------------------------------------

  @Test
  void resetPassword_withWeakPassword_returns400AndDoesNotConsumeToken() throws Exception {
    String token = forgotPasswordAndCaptureToken();

    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, "password12")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("password_too_weak"))
        .andExpect(jsonPath("$.errors[0].password_score").isNumber());

    // The token survives the failed attempt and still works with a strong password.
    mockMvc
        .perform(
            post(RESET_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(resetBody(token, NEW_PASSWORD)))
        .andExpect(status().isNoContent());
  }

  // ---------------------------------------------------------------------------
  // AC-11: 4th forgot-password for the same email within an hour → 429
  // ---------------------------------------------------------------------------

  @Test
  void forgotPassword_fourthRequestForSameEmail_returns429() throws Exception {
    for (int i = 0; i < 3; i++) {
      requestForgotPassword(TEST_EMAIL);
    }
    mockMvc
        .perform(
            post(FORGOT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + TEST_EMAIL + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errors[0].code").value("too_many_attempts"));
  }

  @Test
  void forgotPassword_unknownEmailIsThrottledIdentically() throws Exception {
    String unknown = "ghost-user@example.com";
    for (int i = 0; i < 3; i++) {
      requestForgotPassword(unknown);
    }
    mockMvc
        .perform(
            post(FORGOT_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + unknown + "\"}"))
        .andExpect(status().isTooManyRequests());
  }

  // ---------------------------------------------------------------------------
  // AC-12 ("off" direction): with the helper disabled, the token never hits the logs.
  // The "on" direction is proven by forgotPasswordAndCaptureToken() in every other test.
  // ---------------------------------------------------------------------------

  @Test
  void resetFlow_neverLogsRawTokenOutsideTheGatedDevHelper() throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);

    String token;
    try {
      token = forgotPasswordAndCaptureToken();
      mockMvc
          .perform(
              post(RESET_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(resetBody(token, NEW_PASSWORD)))
          .andExpect(status().isNoContent());
    } finally {
      rootLogger.detachAppender(appender);
    }

    List<String> offendingLines =
        appender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains(token))
            .filter(m -> !m.contains("marker=reset-token-dev"))
            .collect(Collectors.toList());
    assertThat(offendingLines)
        .as("Raw token must appear ONLY in the gated dev-helper line, never elsewhere")
        .isEmpty();
  }
}
