package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring-context integration tests for security wiring concerns that do not fit the per-endpoint
 * tests in PingSmokingIT.
 *
 * <p>Covers:
 *
 * <ul>
 *   <li>AC-7 — BCryptPasswordEncoder is configured with cost ≥ 12
 *   <li>AC-9 — No password, JWT token string, or passwordHash value appears in application logs
 *       during a login + ping flow
 * </ul>
 *
 * <p>Uses the TC JDBC URL from application-test.yml, so Docker is required. Named *IT so that Maven
 * Failsafe picks it up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityWiringIT {

  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;

  // ---------------------------------------------------------------------------
  // AC-7: BCryptPasswordEncoder cost factor ≥ 12
  // ---------------------------------------------------------------------------

  @Test
  void passwordEncoder_isConfiguredWithBcryptCostAtLeast12() throws Exception {
    assertThat(passwordEncoder)
        .as("PasswordEncoder bean must be a BCryptPasswordEncoder")
        .isInstanceOf(BCryptPasswordEncoder.class);

    BCryptPasswordEncoder bCrypt = (BCryptPasswordEncoder) passwordEncoder;

    Field strengthField = BCryptPasswordEncoder.class.getDeclaredField("strength");
    strengthField.setAccessible(true);
    int strength = (int) strengthField.get(bCrypt);

    assertThat(strength)
        .as("BCrypt cost factor (strength) must be ≥ 12 per security baseline")
        .isGreaterThanOrEqualTo(12);
  }

  // ---------------------------------------------------------------------------
  // AC-9: No secret material (password plaintext, JWT string, passwordHash) in logs
  // ---------------------------------------------------------------------------

  /**
   * Exercises the login endpoint and a subsequent /me/ping request while capturing all log output
   * from the root logger. Asserts that no captured log message contains a JWT token (identified by
   * the base64url header prefix "eyJ"), the literal plaintext password, or the string
   * "passwordHash".
   *
   * <p>This test cannot prove absence of secret material in asynchronous or off-thread log
   * contexts, but it covers the synchronous request-processing path of Spring MVC and the
   * application service layer which is where accidental leakage most commonly occurs.
   */
  @Test
  void login_andPing_doNotLeakSecretMaterialToLogs() throws Exception {
    final String testEmail = "wiring-test-trainer@example.com";
    final String testPassword = "SuperSecret-P@ssword-99";

    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();
    User user =
        User.builder()
            .email(testEmail)
            .passwordHash(passwordEncoder.encode(testPassword))
            .roles(Set.of(trainerRole))
            .build();
    userRepository.save(user);

    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);

    String accessToken = null;
    try {
      LoginRequest loginRequest = new LoginRequest(testEmail, testPassword);
      String loginResponseBody =
          mockMvc
              .perform(
                  post("/api/v1/auth/login")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(objectMapper.writeValueAsString(loginRequest)))
              .andReturn()
              .getResponse()
              .getContentAsString();

      // Extract the accessToken value from the JSON response for use in subsequent assertion
      accessToken = objectMapper.readTree(loginResponseBody).path("accessToken").asText(null);

      if (accessToken != null && !accessToken.isBlank()) {
        mockMvc
            .perform(get("/api/v1/me/ping").header("Authorization", "Bearer " + accessToken))
            .andReturn();
      }
    } finally {
      rootLogger.detachAppender(listAppender);
    }

    List<String> logMessages =
        listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

    final String capturedToken = accessToken;
    logMessages.forEach(
        msg -> {
          assertThat(msg)
              .as("Log line must not contain JWT token prefix 'eyJ': %s", msg)
              .doesNotContain("eyJ");

          assertThat(msg)
              .as("Log line must not contain the plaintext password: %s", msg)
              .doesNotContain(testPassword);

          assertThat(msg)
              .as("Log line must not contain the literal string 'passwordHash': %s", msg)
              .doesNotContainIgnoringCase("passwordHash");

          if (capturedToken != null && !capturedToken.isBlank()) {
            assertThat(msg)
                .as("Log line must not contain the full access token string: %s", msg)
                .doesNotContain(capturedToken);
          }
        });
  }
}
