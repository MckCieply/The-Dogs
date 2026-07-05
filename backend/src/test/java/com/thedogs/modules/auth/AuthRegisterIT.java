package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thedogs.modules.auth.dto.RegisterRequest;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Integration tests for AUTH-04: registration endpoint + password strength.
 *
 * <p>Covers AC-1 through AC-10 from docs/specs/auth-register.md. Registered emails all use the
 * {@code register-it+*@example.com} namespace so cleanup cannot collide with other IT classes
 * sharing the same database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthRegisterIT {

  private static final String REGISTER_URL = "/api/v1/auth/register";
  private static final String REFRESH_URL = "/api/v1/auth/refresh";
  private static final String REFRESH_COOKIE_NAME = "refresh_token";

  /** Strong per zxcvbn (random-looking, no dictionary words), length within 10–128. */
  private static final String STRONG_PASSWORD = "kR7#vetch-Quasar93!plinth";

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private UserRepository userRepository;
  @Autowired private RefreshTokenRepository refreshTokenRepository;
  @Autowired private RegistrationRateLimiter registrationRateLimiter;
  @Autowired private PasswordEncoder passwordEncoder;

  @BeforeEach
  void setUp() {
    registrationRateLimiter.clearAll();
    deleteRegisteredUsers();
  }

  @AfterEach
  void tearDown() {
    deleteRegisteredUsers();
  }

  private void deleteRegisteredUsers() {
    List<User> created =
        userRepository.findAll().stream()
            .filter(u -> u.getEmail().toLowerCase().startsWith("register-it"))
            .collect(Collectors.toList());
    for (User u : created) {
      refreshTokenRepository.deleteAll(
          refreshTokenRepository.findAll().stream()
              .filter(t -> t.getUserId().equals(u.getId()))
              .collect(Collectors.toList()));
      userRepository.delete(u);
    }
  }

  private String body(String email, String password, String displayName) throws Exception {
    return objectMapper.writeValueAsString(new RegisterRequest(email, password, displayName));
  }

  // ---------------------------------------------------------------------------
  // AC-1: valid registration → 201 + token pair + cookie + persisted user
  // ---------------------------------------------------------------------------

  @Test
  void register_withValidData_returns201WithTokenPairAndCookie() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(REGISTER_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("register-it+ac1@example.com", STRONG_PASSWORD, "New Trainer")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.tokenType").value("Bearer"))
            .andExpect(jsonPath("$.expiresIn").isNumber())
            .andReturn();

    Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(cookie).as("Registration must set a refresh_token cookie").isNotNull();

    User created = userRepository.findByEmail("register-it+ac1@example.com").orElseThrow();
    assertThat(created.getPasswordHash())
        .as("Password must be stored bcrypt-hashed, never in cleartext")
        .startsWith("$2a$")
        .isNotEqualTo(STRONG_PASSWORD);
    assertThat(created.getRoles()).as("Exactly one role must be assigned").hasSize(1);
  }

  @Test
  void register_thenRefreshWithIssuedCookie_worksEndToEnd() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(REGISTER_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("register-it+e2e@example.com", STRONG_PASSWORD, "E2E User")))
            .andExpect(status().isCreated())
            .andReturn();

    Cookie cookie = result.getResponse().getCookie(REFRESH_COOKIE_NAME);
    assertThat(cookie).isNotNull();

    mockMvc
        .perform(post(REFRESH_URL).cookie(new Cookie(REFRESH_COOKIE_NAME, cookie.getValue())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").isNotEmpty());
  }

  // ---------------------------------------------------------------------------
  // AC-2: duplicate email → 409 email_taken, no new row
  // ---------------------------------------------------------------------------

  @Test
  void register_withDuplicateEmail_returns409EmailTaken() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+dup@example.com", STRONG_PASSWORD, "First")))
        .andExpect(status().isCreated());

    long countBefore = userRepository.count();

    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+dup@example.com", STRONG_PASSWORD, "Second")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("email_taken"));

    assertThat(userRepository.count())
        .as("Duplicate registration must not insert a row")
        .isEqualTo(countBefore);
  }

  @Test
  void register_withDuplicateEmailDifferentCase_returns409EmailTaken() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+case@example.com", STRONG_PASSWORD, "Lower")))
        .andExpect(status().isCreated());

    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("Register-IT+case@EXAMPLE.com", STRONG_PASSWORD, "Upper")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.errors[0].code").value("email_taken"));
  }

  // ---------------------------------------------------------------------------
  // AC-3: weak password → 400 password_too_weak + password_score extension
  // ---------------------------------------------------------------------------

  @Test
  void register_withWeakPassword_returns400WithScoreExtension() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+weak@example.com", "password12", "Weak User")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("password_too_weak"))
        .andExpect(jsonPath("$.errors[0].password_score").isNumber());
  }

  @Test
  void register_withOwnEmailAsPassword_returns400PasswordTooWeak() throws Exception {
    // The user's own email is in the zxcvbn penalty dictionary — cannot be a "strong" password.
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    body(
                        "register-it+selfref@example.com",
                        "register-it+selfref@example.com",
                        "Self Ref")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].code").value("password_too_weak"));
  }

  // ---------------------------------------------------------------------------
  // AC-4: length bounds run before zxcvbn
  // ---------------------------------------------------------------------------

  @Test
  void register_withTooShortPassword_returns400FieldTooShort() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+short@example.com", "kR7#vQ9!", "Short")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[?(@.field == 'password')].code")
                .value(org.hamcrest.Matchers.hasItem("field_too_short")));
  }

  @Test
  void register_withTooLongPassword_returns400FieldTooLong() throws Exception {
    String longPassword = "kR7#vQ".repeat(22); // 132 chars > 128
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+long@example.com", longPassword, "Long")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[?(@.field == 'password')].code")
                .value(org.hamcrest.Matchers.hasItem("field_too_long")));
  }

  // ---------------------------------------------------------------------------
  // AC-5: malformed email → 400 field_invalid_format
  // ---------------------------------------------------------------------------

  @Test
  void register_withMalformedEmail_returns400FieldInvalidFormat() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("not-an-email", STRONG_PASSWORD, "Bad Email")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[?(@.field == 'email')].code")
                .value(org.hamcrest.Matchers.hasItem("field_invalid_format")));
  }

  // ---------------------------------------------------------------------------
  // AC-6: missing/blank display_name → 400 field_required
  // ---------------------------------------------------------------------------

  @Test
  void register_withBlankDisplayName_returns400FieldRequired() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+noname@example.com", STRONG_PASSWORD, "")))
        .andExpect(status().isBadRequest())
        .andExpect(
            jsonPath("$.errors[?(@.field == 'displayName')].code")
                .value(org.hamcrest.Matchers.hasItem("field_required")));
  }

  @Test
  void register_acceptsSnakeCaseDisplayNameAlias() throws Exception {
    String json =
        "{\"email\":\"register-it+snake@example.com\",\"password\":\""
            + STRONG_PASSWORD
            + "\",\"display_name\":\"Snake Case\"}";
    mockMvc
        .perform(post(REGISTER_URL).contentType(MediaType.APPLICATION_JSON).content(json))
        .andExpect(status().isCreated());

    User created = userRepository.findByEmail("register-it+snake@example.com").orElseThrow();
    assertThat(created.getDisplayName()).isEqualTo("Snake Case");
  }

  // ---------------------------------------------------------------------------
  // AC-7: 4th registration from the same IP within an hour → 429 + Retry-After
  // ---------------------------------------------------------------------------

  @Test
  void register_fourthCallFromSameIpWithinHour_returns429WithRetryAfter() throws Exception {
    for (int i = 1; i <= 3; i++) {
      mockMvc
          .perform(
              post(REGISTER_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      body("register-it+rate" + i + "@example.com", STRONG_PASSWORD, "Rate " + i))
                  .with(req -> withIp(req, "203.0.113.7")))
          .andExpect(status().isCreated());
    }

    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+rate4@example.com", STRONG_PASSWORD, "Rate 4"))
                .with(req -> withIp(req, "203.0.113.7")))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.errors[0].code").value("too_many_attempts"))
        .andExpect(header().exists("Retry-After"));
  }

  @Test
  void register_failedValidationDoesNotConsumeRateBudget() throws Exception {
    // 3 weak-password attempts must not lock out the subsequent valid registration.
    for (int i = 1; i <= 3; i++) {
      mockMvc
          .perform(
              post(REGISTER_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("register-it+fv" + i + "@example.com", "password12", "FV"))
                  .with(req -> withIp(req, "203.0.113.8")))
          .andExpect(status().isBadRequest());
    }

    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+fvok@example.com", STRONG_PASSWORD, "FV OK"))
                .with(req -> withIp(req, "203.0.113.8")))
        .andExpect(status().isCreated());
  }

  private static org.springframework.mock.web.MockHttpServletRequest withIp(
      org.springframework.mock.web.MockHttpServletRequest request, String ip) {
    request.setRemoteAddr(ip);
    return request;
  }

  // ---------------------------------------------------------------------------
  // AC-8: first user gets ROLE_ADMIN, later users ROLE_TRAINER; race-safe
  // ---------------------------------------------------------------------------

  @Test
  void register_whenUsersExist_assignsTrainerRoleOnly() throws Exception {
    // Other IT classes (and this suite's setup) guarantee the table is non-empty in the shared
    // context; make it explicit anyway.
    if (userRepository.count() == 0) {
      mockMvc
          .perform(
              post(REGISTER_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("register-it+bootstrap@example.com", STRONG_PASSWORD, "Bootstrap")))
          .andExpect(status().isCreated());
    }

    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+trainer@example.com", STRONG_PASSWORD, "Trainer")))
        .andExpect(status().isCreated());

    User trainer = userRepository.findByEmail("register-it+trainer@example.com").orElseThrow();
    assertThat(trainer.getRoles()).extracting(r -> r.getName()).containsExactly("ROLE_TRAINER");
  }

  @Test
  void register_onEmptyTable_firstUserGetsAdmin_andConcurrentRaceProducesExactlyOneAdmin()
      throws Exception {
    wipeAllUsers();

    ExecutorService executor =
        Executors.newFixedThreadPool(
            2,
            r -> {
              Thread t = new Thread(r);
              t.setDaemon(true);
              return t;
            });
    List<Integer> statuses = new ArrayList<>();
    try {
      CompletableFuture<Integer> f1 = registerAsync(executor, "register-it+race1@example.com");
      CompletableFuture<Integer> f2 = registerAsync(executor, "register-it+race2@example.com");
      statuses.add(f1.get(25, TimeUnit.SECONDS));
      statuses.add(f2.get(25, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
    }

    assertThat(statuses).as("Both concurrent registrations must succeed").containsOnly(201);

    List<User> racers =
        userRepository.findAll().stream()
            .filter(u -> u.getEmail().startsWith("register-it+race"))
            .collect(Collectors.toList());
    long admins =
        racers.stream()
            .filter(u -> u.getRoles().stream().anyMatch(r -> "ROLE_ADMIN".equals(r.getName())))
            .count();
    assertThat(admins)
        .as("Exactly one of two concurrent first-registrations may become admin")
        .isEqualTo(1);
  }

  private CompletableFuture<Integer> registerAsync(ExecutorService executor, String email) {
    return CompletableFuture.supplyAsync(
        () -> {
          try {
            return mockMvc
                .perform(
                    post(REGISTER_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(email, STRONG_PASSWORD, "Racer")))
                .andReturn()
                .getResponse()
                .getStatus();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        },
        executor);
  }

  private void wipeAllUsers() {
    refreshTokenRepository.deleteAll();
    for (User u : userRepository.findAll()) {
      userRepository.delete(u);
    }
  }

  // ---------------------------------------------------------------------------
  // AC-9: bcrypt cost 12
  // ---------------------------------------------------------------------------

  @Test
  void register_storesPasswordWithBcryptCost12() throws Exception {
    mockMvc
        .perform(
            post(REGISTER_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("register-it+cost@example.com", STRONG_PASSWORD, "Cost")))
        .andExpect(status().isCreated());

    User created = userRepository.findByEmail("register-it+cost@example.com").orElseThrow();
    assertThat(created.getPasswordHash()).as("Bcrypt cost must be 12").startsWith("$2a$12$");
    assertThat(passwordEncoder.matches(STRONG_PASSWORD, created.getPasswordHash())).isTrue();
  }

  // ---------------------------------------------------------------------------
  // AC-10: no cleartext password in log output — covered by asserting log messages
  // from the registration flow never include the raw password.
  // ---------------------------------------------------------------------------

  @Test
  void register_doesNotLogCleartextPassword() throws Exception {
    ch.qos.logback.classic.Logger rootLogger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    rootLogger.addAppender(appender);

    try {
      mockMvc
          .perform(
              post(REGISTER_URL)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body("register-it+logs@example.com", STRONG_PASSWORD, "Log Check")))
          .andExpect(status().isCreated());
    } finally {
      rootLogger.detachAppender(appender);
    }

    for (ch.qos.logback.classic.spi.ILoggingEvent event : appender.list) {
      assertThat(event.getFormattedMessage())
          .as("No log line may contain the cleartext password")
          .doesNotContain(STRONG_PASSWORD);
    }
  }

  // ---------------------------------------------------------------------------
  // Response error map sanity: errors array is present with field metadata
  // ---------------------------------------------------------------------------

  @Test
  void register_validationErrorResponse_isRfc7807WithErrorsArray() throws Exception {
    MvcResult result =
        mockMvc
            .perform(
                post(REGISTER_URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body("not-an-email", "x", "")))
            .andExpect(status().isBadRequest())
            .andReturn();

    com.fasterxml.jackson.databind.JsonNode root =
        objectMapper.readTree(result.getResponse().getContentAsString());
    assertThat(root.has("errors")).as("RFC 7807 body must carry the errors array").isTrue();
    assertThat(root.get("errors").isEmpty()).isFalse();
  }
}
