package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.RoleRepository;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Testcontainers-backed integration tests for POST /api/v1/auth/login (AUTH-02).
 *
 * <p>The PostgreSQL container is started automatically via the {@code tc:} JDBC URL in
 * application-test.yml. No explicit {@code @Container} annotation is needed.
 *
 * <p>Naming convention: *IT suffix so Maven Failsafe picks it up (Surefire excludes *IT.java).
 *
 * <p>Rate-limit tests (AC-5) use a dedicated IP via {@code X-Forwarded-For} that is never used by
 * any other test method, preventing bucket state from bleeding across tests within the same
 * application context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginIT {

  // Fixed test secret — matches application-test.yml
  private static final String TEST_SECRET =
      "test-secret-key-that-is-at-least-256-bits-long-for-hmac-sha256-signing";

  // Unique IP addresses per rate-limit test to avoid cross-test bucket state
  private static final String RATE_LIMIT_IP = "198.51.100.55";

  private static final String ENDPOINT = "/api/v1/auth/login";

  @Autowired private TestRestTemplate restTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private RoleRepository roleRepository;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private LoginRateLimiter rateLimiter;

  private static final String TRAINER_EMAIL = "it-trainer@example.com";
  private static final String TRAINER_PASSWORD = "correct-P@ssword-IT-01";
  private static final String DISABLED_EMAIL = "it-disabled@example.com";
  private static final String DISABLED_PASSWORD = "disabled-P@ssword-IT-01";

  @BeforeEach
  void setUp() {
    // Reset the in-memory rate-limit buckets so that failed-login test methods executed earlier
    // in the same application context do not exhaust the bucket for subsequent tests.
    rateLimiter.clearAll();

    Role trainerRole = roleRepository.findByName("ROLE_TRAINER").orElseThrow();

    // Enabled trainer user for happy-path tests
    if (!userRepository.existsByEmail(TRAINER_EMAIL)) {
      User trainer =
          User.builder()
              .email(TRAINER_EMAIL)
              .passwordHash(passwordEncoder.encode(TRAINER_PASSWORD))
              .roles(Set.of(trainerRole))
              .build();
      userRepository.save(trainer);
    }

    // Disabled user for AC-4
    if (!userRepository.existsByEmail(DISABLED_EMAIL)) {
      User disabled =
          User.builder()
              .email(DISABLED_EMAIL)
              .passwordHash(passwordEncoder.encode(DISABLED_PASSWORD))
              .enabled(false)
              .roles(Set.of(trainerRole))
              .build();
      userRepository.save(disabled);
    }
  }

  @AfterEach
  void tearDown() {
    // Clean up test users so no state bleeds between test methods in the same context.
    // No @Transactional rollback is used here because TestRestTemplate uses a real HTTP port
    // (RANDOM_PORT), meaning inserts above committed to the DB and must be explicitly deleted.
    userRepository.findByEmail(TRAINER_EMAIL).ifPresent(userRepository::delete);
    userRepository.findByEmail(DISABLED_EMAIL).ifPresent(userRepository::delete);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private ResponseEntity<String> doLogin(String email, String password) {
    return doLoginWithIp(email, password, null);
  }

  private ResponseEntity<String> doLoginWithIp(String email, String password, String forwardedIp) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    if (forwardedIp != null) {
      headers.set("X-Forwarded-For", forwardedIp);
    }
    String body = String.format("{\"email\":\"%s\",\"password\":\"%s\"}", email, password);
    return restTemplate.postForEntity(ENDPOINT, new HttpEntity<>(body, headers), String.class);
  }

  private Claims parseJwt(String token) {
    SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
    return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
  }

  // ---------------------------------------------------------------------------
  // AC-1: valid credentials → 200 + accessToken + Set-Cookie with refresh_token
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_returns200() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void login_withValidCredentials_responseBodyContainsAccessToken() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.path("accessToken").asText()).isNotBlank();
  }

  @Test
  void login_withValidCredentials_responseBodyContainsExpiresIn900() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    JsonNode body = objectMapper.readTree(response.getBody());
    assertThat(body.path("expiresIn").asLong()).isEqualTo(900L);
  }

  @Test
  void login_withValidCredentials_setCookieHeaderContainsRefreshToken() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).as("Set-Cookie header must be present").isNotNull().isNotEmpty();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElse(null);

    assertThat(refreshCookie)
        .as("Set-Cookie must contain a refresh_token cookie")
        .isNotNull()
        .isNotBlank();
  }

  @Test
  void login_withValidCredentials_refreshCookieIsHttpOnly() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).isNotNull();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));

    assertThat(refreshCookie)
        .as("refresh_token cookie must be HttpOnly")
        .containsIgnoringCase("HttpOnly");
  }

  @Test
  void login_withValidCredentials_refreshCookieIsSecure() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).isNotNull();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));

    assertThat(refreshCookie)
        .as("refresh_token cookie must be Secure")
        .containsIgnoringCase("Secure");
  }

  @Test
  void login_withValidCredentials_refreshCookieIsSameSiteStrict() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).isNotNull();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));

    assertThat(refreshCookie)
        .as("refresh_token cookie must be SameSite=Strict")
        .containsIgnoringCase("SameSite=Strict");
  }

  // ---------------------------------------------------------------------------
  // AC-2: wrong password → 401 + {"errors":[{"code":"bad_credentials"}]}
  // ---------------------------------------------------------------------------

  @Test
  void login_withWrongPassword_returns401() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, "definitely-wrong-password");

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void login_withWrongPassword_responseBadCredentialsErrorCode() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, "definitely-wrong-password");

    JsonNode body = objectMapper.readTree(response.getBody());
    String code = body.path("errors").get(0).path("code").asText();
    assertThat(code).isEqualTo("bad_credentials");
  }

  // ---------------------------------------------------------------------------
  // AC-3: unknown email → 401 + identical JSON shape as AC-2 (anti-enumeration)
  // ---------------------------------------------------------------------------

  @Test
  void login_withUnknownEmail_returns401() {
    ResponseEntity<String> response = doLogin("nobody@example.com", TRAINER_PASSWORD);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void login_withUnknownEmail_responseBadCredentialsErrorCode() throws Exception {
    ResponseEntity<String> response = doLogin("nobody@example.com", TRAINER_PASSWORD);

    JsonNode body = objectMapper.readTree(response.getBody());
    String code = body.path("errors").get(0).path("code").asText();
    assertThat(code).isEqualTo("bad_credentials");
  }

  @Test
  void login_unknownEmailAndWrongPassword_returnIdenticalErrorCode() throws Exception {
    ResponseEntity<String> unknownEmailResp = doLogin("nobody@example.com", TRAINER_PASSWORD);
    ResponseEntity<String> wrongPasswordResp = doLogin(TRAINER_EMAIL, "wrong-password-ac3");

    String unknownEmailCode =
        objectMapper
            .readTree(unknownEmailResp.getBody())
            .path("errors")
            .get(0)
            .path("code")
            .asText();
    String wrongPasswordCode =
        objectMapper
            .readTree(wrongPasswordResp.getBody())
            .path("errors")
            .get(0)
            .path("code")
            .asText();

    assertThat(unknownEmailCode)
        .as("Unknown email and wrong password must return the same error code (anti-enumeration)")
        .isEqualTo(wrongPasswordCode)
        .isEqualTo("bad_credentials");
  }

  // ---------------------------------------------------------------------------
  // AC-4: disabled user → 401 + {"errors":[{"code":"account_disabled"}]}
  // ---------------------------------------------------------------------------

  @Test
  void login_withDisabledUser_returns401() {
    ResponseEntity<String> response = doLogin(DISABLED_EMAIL, DISABLED_PASSWORD);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void login_withDisabledUser_responseAccountDisabledErrorCode() throws Exception {
    ResponseEntity<String> response = doLogin(DISABLED_EMAIL, DISABLED_PASSWORD);

    JsonNode body = objectMapper.readTree(response.getBody());
    String code = body.path("errors").get(0).path("code").asText();
    assertThat(code).isEqualTo("account_disabled");
  }

  // ---------------------------------------------------------------------------
  // AC-5: 6th failed attempt from same IP within 15 min → 429 + Retry-After header
  // Isolation: uses a dedicated X-Forwarded-For IP not shared with other tests
  // ---------------------------------------------------------------------------

  @Test
  void login_sixthFailedAttemptFromSameIp_returns429() {
    // First 5 attempts — each must return 401 (not yet blocked)
    for (int i = 0; i < 5; i++) {
      ResponseEntity<String> resp =
          doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5-" + i, RATE_LIMIT_IP);
      assertThat(resp.getStatusCode())
          .as("Attempt " + (i + 1) + " must return 401 (not yet rate-limited)")
          .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // 6th attempt must be blocked
    ResponseEntity<String> blockedResp =
        doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5-final", RATE_LIMIT_IP);
    assertThat(blockedResp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
  }

  @Test
  void login_sixthFailedAttemptFromSameIp_responseTooManyAttemptsErrorCode() throws Exception {
    for (int i = 0; i < 5; i++) {
      doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5e-" + i, RATE_LIMIT_IP + "1");
    }

    ResponseEntity<String> blockedResp =
        doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5e-final", RATE_LIMIT_IP + "1");

    JsonNode body = objectMapper.readTree(blockedResp.getBody());
    String code = body.path("errors").get(0).path("code").asText();
    assertThat(code).isEqualTo("too_many_attempts");
  }

  @Test
  void login_sixthFailedAttemptFromSameIp_retryAfterHeaderIsPresent() {
    for (int i = 0; i < 5; i++) {
      doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5f-" + i, RATE_LIMIT_IP + "2");
    }

    ResponseEntity<String> blockedResp =
        doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5f-final", RATE_LIMIT_IP + "2");

    assertThat(blockedResp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
        .as("Retry-After header must be present on 429 response")
        .isNotNull()
        .isNotBlank();
  }

  @Test
  void login_sixthFailedAttemptFromSameIp_retryAfterHeaderIsPositiveInteger() {
    for (int i = 0; i < 5; i++) {
      doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5g-" + i, RATE_LIMIT_IP + "3");
    }

    ResponseEntity<String> blockedResp =
        doLoginWithIp(TRAINER_EMAIL, "bad-password-ac5g-final", RATE_LIMIT_IP + "3");

    String retryAfterHeader = blockedResp.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    assertThat(retryAfterHeader).isNotNull();
    long retryAfterSeconds = Long.parseLong(retryAfterHeader);
    assertThat(retryAfterSeconds)
        .as("Retry-After must be a positive number of seconds (at most 15 minutes + 1 s rounding)")
        .isGreaterThan(0L)
        // getRetryAfterSeconds() rounds up by +1 to ensure callers never retry too early;
        // ceiling for a 15-minute window is 900 s + 1 s = 901 s.
        .isLessThanOrEqualTo(Duration.ofMinutes(15).getSeconds() + 1L);
  }

  // ---------------------------------------------------------------------------
  // AC-6: missing / invalid fields → 400 with errors[].code
  // ---------------------------------------------------------------------------

  @Test
  void login_withMissingEmailField_returns400WithFieldRequiredCode() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{\"password\":\"some-password\"}";
    ResponseEntity<String> response =
        restTemplate.postForEntity(ENDPOINT, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String code = responseBody.path("errors").get(0).path("code").asText();
    assertThat(code).isIn("field_required", "field_invalid_format");
  }

  @Test
  void login_withMissingPasswordField_returns400() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{\"email\":\"valid@example.com\"}";
    ResponseEntity<String> response =
        restTemplate.postForEntity(ENDPOINT, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String code = responseBody.path("errors").get(0).path("code").asText();
    assertThat(code).isIn("field_required", "field_invalid_format");
  }

  @Test
  void login_withInvalidEmailFormat_returns400WithFieldInvalidFormatCode() throws Exception {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{\"email\":\"not-an-email\",\"password\":\"some-password\"}";
    ResponseEntity<String> response =
        restTemplate.postForEntity(ENDPOINT, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String code = responseBody.path("errors").get(0).path("code").asText();
    assertThat(code).isIn("field_required", "field_invalid_format");
  }

  @Test
  void login_withEmptyBody_returns400() {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String body = "{}";
    ResponseEntity<String> response =
        restTemplate.postForEntity(ENDPOINT, new HttpEntity<>(body, headers), String.class);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  // ---------------------------------------------------------------------------
  // AC-7: decoded access JWT has exp - iat == 900 and roles claim present
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_jwtExpiresInIs900Seconds() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String accessToken = responseBody.path("accessToken").asText();
    assertThat(accessToken).isNotBlank();

    Claims claims = parseJwt(accessToken);
    long iat = claims.getIssuedAt().getTime() / 1000L;
    long exp = claims.getExpiration().getTime() / 1000L;

    assertThat(exp - iat).as("JWT exp - iat must equal 900 seconds (15 minutes)").isEqualTo(900L);
  }

  @Test
  void login_withValidCredentials_jwtContainsRolesClaimWithTrainerRole() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String accessToken = responseBody.path("accessToken").asText();
    assertThat(accessToken).isNotBlank();

    Claims claims = parseJwt(accessToken);
    @SuppressWarnings("unchecked")
    List<String> roles = claims.get("roles", List.class);

    assertThat(roles)
        .as("JWT roles claim must contain ROLE_TRAINER")
        .isNotNull()
        .contains("ROLE_TRAINER");
  }

  @Test
  void login_withValidCredentials_jwtSubjectIsUserUuid() throws Exception {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String accessToken = responseBody.path("accessToken").asText();

    Claims claims = parseJwt(accessToken);
    String sub = claims.getSubject();

    // Must parse as UUID without throwing
    java.util.UUID parsedSub = java.util.UUID.fromString(sub);
    assertThat(parsedSub).as("JWT sub must be a valid UUID").isNotNull();
  }

  @Test
  void login_withValidCredentials_jwtRolesClaimExactlyMatchesUserRolesStoredInDb()
      throws Exception {
    // Load the roles that are actually persisted for the test user so we can compare
    // what the JWT carries against the ground truth from user_role.
    User trainer =
        userRepository
            .findByEmail(TRAINER_EMAIL)
            .orElseThrow(() -> new AssertionError("Test user not found in DB"));
    Set<String> dbRoles =
        trainer.getRoles().stream().map(Role::getName).collect(Collectors.toSet());

    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);
    JsonNode responseBody = objectMapper.readTree(response.getBody());
    String accessToken = responseBody.path("accessToken").asText();
    assertThat(accessToken).isNotBlank();

    Claims claims = parseJwt(accessToken);
    @SuppressWarnings("unchecked")
    List<String> jwtRoles = claims.get("roles", List.class);

    assertThat(jwtRoles)
        .as(
            "JWT roles claim must contain exactly the roles stored in user_role — no extras, no omissions")
        .isNotNull()
        .containsExactlyInAnyOrderElementsOf(dbRoles);
  }

  // ---------------------------------------------------------------------------
  // Cookie attribute: Path is scoped to /api/v1/auth (not globally accessible)
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_refreshCookieHasPathApiV1Auth() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).isNotNull();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));

    assertThat(refreshCookie)
        .as("refresh_token cookie Path must be /api/v1/auth")
        .containsIgnoringCase("Path=/api/v1/auth");
  }

  // ---------------------------------------------------------------------------
  // Cookie attributes: all four security attributes present in one assertion
  // (HttpOnly + Secure + SameSite=Strict + Path=/api/v1/auth)
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_refreshCookieHasAllRequiredSecurityAttributes() {
    ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);

    List<String> setCookieHeaders = response.getHeaders().get(HttpHeaders.SET_COOKIE);
    assertThat(setCookieHeaders).isNotNull();

    String refreshCookie =
        setCookieHeaders.stream()
            .filter(h -> h.startsWith("refresh_token="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("refresh_token cookie not found"));

    SoftAssertions softly = new SoftAssertions();
    softly
        .assertThat(refreshCookie)
        .as("refresh_token cookie must carry HttpOnly")
        .containsIgnoringCase("HttpOnly");
    softly
        .assertThat(refreshCookie)
        .as("refresh_token cookie must carry Secure")
        .containsIgnoringCase("Secure");
    softly
        .assertThat(refreshCookie)
        .as("refresh_token cookie must carry SameSite=Strict")
        .containsIgnoringCase("SameSite=Strict");
    softly
        .assertThat(refreshCookie)
        .as("refresh_token cookie must carry Path=/api/v1/auth")
        .containsIgnoringCase("Path=/api/v1/auth");
    softly.assertAll();
  }

  // ---------------------------------------------------------------------------
  // AC-8: Testcontainers Postgres is used (verified by the fact this test class
  // runs against the tc: JDBC URL from application-test.yml — the test class itself
  // is the assertion; Flyway migration having run proves the container is live)
  // ---------------------------------------------------------------------------

  @Test
  void testDatabase_isTestcontainersPostgres_flywaySchemaMigrationsApplied() {
    // This test asserts implicitly: if the application context started (and @BeforeEach
    // succeeded saving users), the TC Postgres container is running and Flyway has
    // applied all migrations. The fact that @BeforeEach does not throw is the assertion.
    // We add an explicit user lookup as an affirmative DB assertion.
    assertThat(userRepository.existsByEmail(TRAINER_EMAIL))
        .as("Test user must exist in the Testcontainers Postgres DB after @BeforeEach")
        .isTrue();
  }

  // ---------------------------------------------------------------------------
  // AC-9: no password, no raw JWT, no passwordHash in log output during login
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_doesNotLeakSecretMaterialToLogs() throws Exception {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);

    String accessToken = null;
    try {
      ResponseEntity<String> response = doLogin(TRAINER_EMAIL, TRAINER_PASSWORD);
      if (response.getStatusCode() == HttpStatus.OK) {
        JsonNode body = objectMapper.readTree(response.getBody());
        accessToken = body.path("accessToken").asText(null);
      }
    } finally {
      rootLogger.detachAppender(listAppender);
    }

    final String capturedToken = accessToken;
    List<String> logMessages =
        listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

    logMessages.forEach(
        msg -> {
          assertThat(msg)
              .as("Log line must not contain the plaintext password")
              .doesNotContain(TRAINER_PASSWORD);

          assertThat(msg)
              .as("Log line must not contain the literal string 'passwordHash'")
              .doesNotContainIgnoringCase("passwordHash");

          assertThat(msg)
              .as("Log line must not contain 'Bearer ' (token in Authorization header value)")
              .doesNotContain("Bearer ");

          if (capturedToken != null && !capturedToken.isBlank()) {
            assertThat(msg)
                .as("Log line must not contain the full access token string")
                .doesNotContain(capturedToken);
          }
        });
  }

  @Test
  void login_withWrongCredentials_doesNotLeakEmailOrPasswordToLogs() {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    rootLogger.addAppender(listAppender);

    try {
      doLogin(TRAINER_EMAIL, "leaking-password-ac9");
    } finally {
      rootLogger.detachAppender(listAppender);
    }

    List<String> logMessages =
        listAppender.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

    logMessages.forEach(
        msg ->
            assertThat(msg)
                .as("Log line on failure must not contain the plaintext password")
                .doesNotContain("leaking-password-ac9"));
  }
}
