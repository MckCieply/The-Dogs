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
import com.thedogs.modules.auth.dto.RegisterRequest;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for AUTH-09: email confirmation (admin-token flow, no SMTP).
 *
 * <p>Registered emails all use the {@code verify-it+*@example.com} namespace so cleanup cannot
 * collide with other IT classes sharing the same database. Raw token values are captured from the
 * dev-mode log helper (enabled in the test profile), mirroring AuthPasswordResetIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationIT {

  private static final String REGISTER_URL = "/api/v1/auth/register";
  private static final String LOGIN_URL = "/api/v1/auth/login";
  private static final String CONFIRM_URL = "/api/v1/auth/confirm-email";
  private static final String RESEND_URL = "/api/v1/auth/resend-confirmation";
  private static final String ADMIN_TOKENS_URL = "/api/v1/admin/email-verification-tokens";

  private static final String STRONG_PASSWORD = "kR7#vetch-Quasar93!plinth";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private EmailVerificationTokenRepository verificationTokenRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private RegistrationRateLimiter registrationRateLimiter;
  @Autowired private EmailVerificationRateLimiter verificationRateLimiter;
  @Autowired private LoginRateLimiter loginRateLimiter;

  @BeforeEach
  void setUp() {
    registrationRateLimiter.clearAll();
    verificationRateLimiter.clearAll();
    loginRateLimiter.clearAll();
    cleanup();
  }

  @AfterEach
  void tearDown() {
    cleanup();
  }

  private void cleanup() {
    for (User u :
        userRepository.findAll().stream()
            .filter(x -> x.getEmail().toLowerCase().startsWith("verify-it"))
            .collect(Collectors.toList())) {
      verificationTokenRepository.deleteAll(
          verificationTokenRepository.findAll().stream()
              .filter(t -> t.getUserId().equals(u.getId()))
              .collect(Collectors.toList()));
      refreshTokenRepository.deleteAll(
          refreshTokenRepository.findAll().stream()
              .filter(t -> t.getUserId().equals(u.getId()))
              .collect(Collectors.toList()));
      userRepository.delete(u);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private String registerBody(String email) throws Exception {
    return objectMapper.writeValueAsString(
        new RegisterRequest(email, STRONG_PASSWORD, "Verify IT"));
  }

  /** Registers a user and captures the raw verification token from the dev-mode log helper. */
  private String registerAndCaptureToken(String email) throws Exception {
    Logger serviceLogger = (Logger) LoggerFactory.getLogger(EmailVerificationService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      mockMvc
          .perform(
              post(REGISTER_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(registerBody(email)))
          .andExpect(status().isCreated());
    } finally {
      serviceLogger.detachAppender(appender);
    }
    return captureTokenFrom(appender);
  }

  /** Calls resend-confirmation and captures the freshly rotated raw token from the dev log. */
  private String resendAndCaptureToken(String email) throws Exception {
    Logger serviceLogger = (Logger) LoggerFactory.getLogger(EmailVerificationService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    serviceLogger.addAppender(appender);
    try {
      mockMvc
          .perform(
              post(RESEND_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"email\":\"" + email + "\"}"))
          .andExpect(status().isNoContent());
    } finally {
      serviceLogger.detachAppender(appender);
    }
    return captureTokenFrom(appender);
  }

  private static String captureTokenFrom(ListAppender<ILoggingEvent> appender) {
    return appender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .filter(m -> m.contains("marker=verify-token-dev"))
        .map(m -> m.replaceAll(".*token=([0-9a-f]{64}).*", "$1"))
        .findFirst()
        .orElseThrow(
            () ->
                new AssertionError(
                    "Dev-mode token log line not found — helper must be on in test profile"));
  }

  private void confirm(String token) throws Exception {
    mockMvc
        .perform(
            post(CONFIRM_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isNoContent());
  }

  private org.springframework.test.web.servlet.ResultActions login(String email) throws Exception {
    return mockMvc.perform(
        post(LOGIN_URL)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + STRONG_PASSWORD + "\"}"));
  }

  // ---------------------------------------------------------------------------
  // Registration mints an unverified account + a token; login is gated
  // ---------------------------------------------------------------------------

  @Test
  void register_createsUnverifiedUser_andLoginIsBlockedUntilConfirmed() throws Exception {
    String email = "verify-it+gate@example.com";
    String token = registerAndCaptureToken(email);

    User created = userRepository.findByEmail(email).orElseThrow();
    assertThat(created.isEmailVerified()).isFalse();

    login(email)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("email_not_verified"));

    confirm(token);

    assertThat(userRepository.findByEmail(email).orElseThrow().isEmailVerified()).isTrue();
    login(email).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  @Test
  void loginGate_doesNotRevealVerificationStatusOnWrongPassword() throws Exception {
    String email = "verify-it+probe@example.com";
    registerAndCaptureToken(email);

    // Wrong password on an unverified account must yield bad_credentials, NOT email_not_verified.
    mockMvc
        .perform(
            post(LOGIN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"wrong-Pass#99!word\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.errors[0].code").value("bad_credentials"));
  }

  // ---------------------------------------------------------------------------
  // Token consumption: single-use, garbage rejected, one error code for all
  // ---------------------------------------------------------------------------

  @Test
  void confirmEmail_withUnknownToken_returns400InvalidVerificationToken() throws Exception {
    mockMvc
        .perform(
            post(CONFIRM_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + "ab".repeat(32) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_verification_token"));
  }

  @Test
  void confirmEmail_tokenIsSingleUse() throws Exception {
    String email = "verify-it+reuse@example.com";
    String token = registerAndCaptureToken(email);

    confirm(token);

    mockMvc
        .perform(
            post(CONFIRM_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_verification_token"));
  }

  // ---------------------------------------------------------------------------
  // Resend: anti-enumeration 204 for unknown emails, rotation invalidates old token
  // ---------------------------------------------------------------------------

  @Test
  void resendConfirmation_withUnknownEmail_returns204() throws Exception {
    mockMvc
        .perform(
            post(RESEND_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"verify-it+ghost@example.com\"}"))
        .andExpect(status().isNoContent());
  }

  @Test
  void resendConfirmation_rotatesToken_oldTokenBecomesInvalid() throws Exception {
    String email = "verify-it+rotate@example.com";
    String original = registerAndCaptureToken(email);

    String rotated = resendAndCaptureToken(email);
    assertThat(rotated).isNotEqualTo(original);

    mockMvc
        .perform(
            post(CONFIRM_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + original + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("invalid_verification_token"));

    confirm(rotated);
    login(email).andExpect(status().isOk());
  }

  @Test
  void resendConfirmation_fourthCallForSameEmailWithinHour_returns429() throws Exception {
    String email = "verify-it+resend-rate@example.com";
    registerAndCaptureToken(email);

    for (int i = 0; i < 3; i++) {
      mockMvc
          .perform(
              post(RESEND_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"email\":\"" + email + "\"}"))
          .andExpect(status().isNoContent());
    }

    mockMvc
        .perform(
            post(RESEND_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errors[0].code").value("too_many_attempts"));
  }

  // ---------------------------------------------------------------------------
  // Admin retrieval — ADMIN 200 (rotated), TRAINER 403, verified/unknown 404
  // ---------------------------------------------------------------------------

  @Test
  void adminGetToken_asAdmin_returnsFreshTokenThatWorks() throws Exception {
    String email = "verify-it+admin@example.com";
    String original = registerAndCaptureToken(email);

    var result =
        mockMvc
            .perform(
                get(ADMIN_TOKENS_URL)
                    .param("email", email)
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
    assertThat(adminToken).isNotEqualTo(original);

    // Rotation must have invalidated the original value.
    mockMvc
        .perform(
            post(CONFIRM_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + original + "\"}"))
        .andExpect(status().isBadRequest());

    confirm(adminToken);
    login(email).andExpect(status().isOk());
  }

  @Test
  void adminGetToken_asTrainer_returns403Forbidden() throws Exception {
    mockMvc
        .perform(
            get(ADMIN_TOKENS_URL)
                .param("email", "verify-it+any@example.com")
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("trainer")
                        .roles("TRAINER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void adminGetToken_forVerifiedAccount_returns404() throws Exception {
    String email = "verify-it+done@example.com";
    String token = registerAndCaptureToken(email);
    confirm(token);

    mockMvc
        .perform(
            get(ADMIN_TOKENS_URL)
                .param("email", email)
                .with(
                    org.springframework.security.test.web.servlet.request
                        .SecurityMockMvcRequestPostProcessors.user("admin")
                        .roles("ADMIN")))
        .andExpect(status().isNotFound());
  }
}
