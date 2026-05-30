package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.user.Role;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Mockito unit tests for AuthService.login() (AUTH-02 / AUTH-03).
 *
 * <p>No Spring context, no DB, no Docker required. All collaborators are mocked except
 * PasswordEncoder, which is also mocked (with the constructor's encode() call stubbed to return a
 * dummy hash string). Bucket4j and IpAddressExtractor are bypassed by mocking LoginRateLimiter and
 * a mock HttpServletRequest.
 */
class AuthServiceLoginTest {

  private static final String TEST_IP = "203.0.113.10";
  private static final String TEST_EMAIL = "trainer@example.com";
  private static final String TEST_PASSWORD = "correct-password";
  private static final String DUMMY_HASH = "$2a$12$dummyhashplaceholderfortest";
  private static final String ACCESS_TOKEN = "header.payload.signature";
  private static final String REFRESH_TOKEN = "abc123refreshtoken";
  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  private UserRepository userRepository;
  private PasswordEncoder passwordEncoder;
  private LoginRateLimiter rateLimiter;
  private TokenService tokenService;
  private RefreshTokenService refreshTokenService;
  private AuthService authService;
  private HttpServletRequest httpRequest;
  private User enabledUser;

  @BeforeEach
  void setUp() throws Exception {
    userRepository = mock(UserRepository.class);
    passwordEncoder = mock(PasswordEncoder.class);
    rateLimiter = mock(LoginRateLimiter.class);
    tokenService = mock(TokenService.class);
    refreshTokenService = mock(RefreshTokenService.class);
    httpRequest = mock(HttpServletRequest.class);

    // Stub the constructor-time encode() call that builds dummyHash
    when(passwordEncoder.encode("dummy-password-for-timing-auth02")).thenReturn(DUMMY_HASH);

    authService =
        new AuthService(
            tokenService, refreshTokenService, userRepository, rateLimiter, passwordEncoder);

    // Build an enabled test user — set ID via reflection so there is no DB involved
    Role trainerRole = Role.builder().id((short) 1).name("ROLE_TRAINER").build();
    enabledUser =
        User.builder()
            .email(TEST_EMAIL)
            .passwordHash("stored-bcrypt-hash")
            .roles(Set.of(trainerRole))
            .build();
    var idField = com.thedogs.common.BaseEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(enabledUser, USER_ID);

    // Default HTTP request stubs — direct TCP peer, no proxy headers
    when(httpRequest.getHeader("CF-Connecting-IP")).thenReturn(null);
    when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
    when(httpRequest.getHeader("User-Agent")).thenReturn("Test/1.0");
    when(httpRequest.getRemoteAddr()).thenReturn(TEST_IP);

    // Default token stubs
    when(tokenService.generateAccessToken(enabledUser)).thenReturn(ACCESS_TOKEN);
    when(tokenService.generateRefreshToken()).thenReturn(REFRESH_TOKEN);
    when(tokenService.getAccessTokenTtlSeconds()).thenReturn(900L);
    when(tokenService.getRefreshTokenTtlSeconds()).thenReturn(604800L);

    // Default refreshTokenService.issue() stub — returns a dummy entity
    when(refreshTokenService.issue(any(), any(), any(), isNull(), anyString(), anyString(), anyString()))
        .thenReturn(mock(RefreshToken.class));
  }

  // ---------------------------------------------------------------------------
  // Happy path: valid credentials → LoginResult with accessToken and refreshToken
  // ---------------------------------------------------------------------------

  @Test
  void login_withValidCredentials_returnsLoginResultWithAccessToken() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    AuthService.LoginResult result =
        authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);

    assertThat(result).isNotNull();
    assertThat(result.loginResponse()).isNotNull();
    assertThat(result.loginResponse().accessToken()).isEqualTo(ACCESS_TOKEN);
  }

  @Test
  void login_withValidCredentials_returnsLoginResultWithRefreshToken() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    AuthService.LoginResult result =
        authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);

    assertThat(result.refreshToken()).isEqualTo(REFRESH_TOKEN);
  }

  @Test
  void login_withValidCredentials_persistsRefreshTokenViaService() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);

    // Verify issue() is called with correct userId, a non-null expiry, a new family UUID, null
    // parent, and a non-null hash — we cannot check the exact hash without re-implementing SHA-256
    verify(refreshTokenService)
        .issue(
            eq(USER_ID),
            any(Instant.class),
            any(UUID.class),
            isNull(),
            anyString(),
            anyString(),
            anyString());
  }

  @Test
  void login_withValidCredentials_doesNotCallRecordFailure() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);

    verify(rateLimiter, never()).recordFailure(anyString());
  }

  @Test
  void login_withValidCredentials_emitsInfoLevelAuditLog() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    Logger authServiceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    authServiceLogger.addAppender(listAppender);

    try {
      authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);
    } finally {
      authServiceLogger.detachAppender(listAppender);
    }

    List<String> infoMessages =
        listAppender.list.stream()
            .filter(e -> e.getLevel() == Level.INFO)
            .map(ILoggingEvent::getFormattedMessage)
            .collect(Collectors.toList());

    assertThat(infoMessages)
        .as("At least one INFO-level audit log must be emitted on successful login")
        .anyMatch(msg -> msg.contains("event=login_success"));
  }

  @Test
  void login_withValidCredentials_auditLogContainsUserIdNotPassword() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(TEST_PASSWORD, enabledUser.getPasswordHash())).thenReturn(true);

    Logger authServiceLogger = (Logger) LoggerFactory.getLogger(AuthService.class);
    ListAppender<ILoggingEvent> listAppender = new ListAppender<>();
    listAppender.start();
    authServiceLogger.addAppender(listAppender);

    try {
      authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);
    } finally {
      authServiceLogger.detachAppender(listAppender);
    }

    listAppender.list.stream()
        .map(ILoggingEvent::getFormattedMessage)
        .forEach(
            msg -> {
              assertThat(msg)
                  .as("Audit log must not contain the plaintext password")
                  .doesNotContain(TEST_PASSWORD);
              assertThat(msg)
                  .as("Audit log must not contain the raw email address")
                  .doesNotContain(TEST_EMAIL);
            });
  }

  // ---------------------------------------------------------------------------
  // Unknown email: dummy bcrypt runs, BadCredentialsException thrown, failure recorded
  // ---------------------------------------------------------------------------

  @Test
  void login_withUnknownEmail_throwsBadCredentialsException() {
    when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
    // matches() is still called for timing equalisation (against dummyHash)
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(false);

    assertThatThrownBy(
            () ->
                authService.login(
                    new LoginRequest("unknown@example.com", TEST_PASSWORD), httpRequest))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void login_withUnknownEmail_callsDummyBcryptToEqualiseTiming() {
    when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(false);

    try {
      authService.login(new LoginRequest("unknown@example.com", TEST_PASSWORD), httpRequest);
    } catch (BadCredentialsException ignored) {
    }

    // Verify that matches() was called against the dummy hash (timing equalisation)
    verify(passwordEncoder).matches(TEST_PASSWORD, DUMMY_HASH);
  }

  @Test
  void login_withUnknownEmail_recordsFailureForIp() {
    when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(false);

    try {
      authService.login(new LoginRequest("unknown@example.com", TEST_PASSWORD), httpRequest);
    } catch (BadCredentialsException ignored) {
    }

    verify(rateLimiter).recordFailure(TEST_IP);
  }

  // ---------------------------------------------------------------------------
  // Wrong password: user found, password mismatch, BadCredentialsException, failure recorded
  // ---------------------------------------------------------------------------

  @Test
  void login_withWrongPassword_throwsBadCredentialsException() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(anyString(), eq(enabledUser.getPasswordHash()))).thenReturn(false);

    assertThatThrownBy(
            () -> authService.login(new LoginRequest(TEST_EMAIL, "wrong-password"), httpRequest))
        .isInstanceOf(BadCredentialsException.class);
  }

  @Test
  void login_withWrongPassword_recordsFailureForIp() {
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(anyString(), eq(enabledUser.getPasswordHash()))).thenReturn(false);

    try {
      authService.login(new LoginRequest(TEST_EMAIL, "wrong-password"), httpRequest);
    } catch (BadCredentialsException ignored) {
    }

    verify(rateLimiter).recordFailure(TEST_IP);
  }

  // ---------------------------------------------------------------------------
  // Disabled user: found but enabled=false → AccountDisabledException, no failure recorded
  // ---------------------------------------------------------------------------

  @Test
  void login_withDisabledUser_throwsAccountDisabledException() throws Exception {
    // Build a disabled variant
    Role trainerRole = Role.builder().id((short) 1).name("ROLE_TRAINER").build();
    User disabledUser =
        User.builder()
            .email("disabled@example.com")
            .passwordHash("stored-bcrypt-hash")
            .enabled(false)
            .roles(Set.of(trainerRole))
            .build();
    var idField = com.thedogs.common.BaseEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(disabledUser, UUID.fromString("00000000-0000-0000-0000-000000000002"));

    when(userRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(disabledUser));

    assertThatThrownBy(
            () ->
                authService.login(
                    new LoginRequest("disabled@example.com", TEST_PASSWORD), httpRequest))
        .isInstanceOf(AccountDisabledException.class);
  }

  @Test
  void login_withDisabledUser_doesNotRecordFailureForIp() throws Exception {
    Role trainerRole = Role.builder().id((short) 1).name("ROLE_TRAINER").build();
    User disabledUser =
        User.builder()
            .email("disabled@example.com")
            .passwordHash("stored-bcrypt-hash")
            .enabled(false)
            .roles(Set.of(trainerRole))
            .build();
    var idField = com.thedogs.common.BaseEntity.class.getDeclaredField("id");
    idField.setAccessible(true);
    idField.set(disabledUser, UUID.fromString("00000000-0000-0000-0000-000000000003"));

    when(userRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(disabledUser));

    try {
      authService.login(new LoginRequest("disabled@example.com", TEST_PASSWORD), httpRequest);
    } catch (AccountDisabledException ignored) {
    }

    // Disabled user is operator state, not brute-force — failure must NOT be recorded
    verify(rateLimiter, never()).recordFailure(anyString());
  }

  // ---------------------------------------------------------------------------
  // Rate limit already exceeded: checkLimit() throws → TooManyLoginAttemptsException propagated
  // without any DB lookup
  // ---------------------------------------------------------------------------

  @Test
  void login_whenRateLimitAlreadyExceeded_propagatesTooManyLoginAttemptsException() {
    doThrow(new TooManyLoginAttemptsException(540L)).when(rateLimiter).checkLimit(TEST_IP);

    assertThatThrownBy(
            () -> authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest))
        .isInstanceOf(TooManyLoginAttemptsException.class)
        .extracting(ex -> ((TooManyLoginAttemptsException) ex).getRetryAfterSeconds())
        .isEqualTo(540L);
  }

  @Test
  void login_whenRateLimitAlreadyExceeded_doesNotQueryDatabase() {
    doThrow(new TooManyLoginAttemptsException(540L)).when(rateLimiter).checkLimit(TEST_IP);

    try {
      authService.login(new LoginRequest(TEST_EMAIL, TEST_PASSWORD), httpRequest);
    } catch (TooManyLoginAttemptsException ignored) {
    }

    // No DB lookup must occur when rate limit is already exceeded
    verify(userRepository, never()).findByEmail(anyString());
  }

  // ---------------------------------------------------------------------------
  // Anti-enumeration: unknown email and wrong password both throw BadCredentialsException
  // with the same exception type (identical class, same message)
  // ---------------------------------------------------------------------------

  @Test
  void login_unknownEmailAndWrongPassword_throwSameExceptionType() {
    // Unknown email path
    when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(false);

    // Wrong password path
    when(userRepository.findByEmail(TEST_EMAIL)).thenReturn(Optional.of(enabledUser));
    when(passwordEncoder.matches(anyString(), eq(enabledUser.getPasswordHash()))).thenReturn(false);

    Class<?> unknownEmailExType = null;
    String unknownEmailExMessage = null;
    Class<?> wrongPasswordExType = null;
    String wrongPasswordExMessage = null;

    try {
      authService.login(new LoginRequest("nobody@example.com", TEST_PASSWORD), httpRequest);
    } catch (Exception e) {
      unknownEmailExType = e.getClass();
      unknownEmailExMessage = e.getMessage();
    }

    try {
      authService.login(new LoginRequest(TEST_EMAIL, "wrong-password"), httpRequest);
    } catch (Exception e) {
      wrongPasswordExType = e.getClass();
      wrongPasswordExMessage = e.getMessage();
    }

    assertThat(unknownEmailExType)
        .as("Unknown email and wrong password must produce the same exception class")
        .isEqualTo(wrongPasswordExType)
        .isEqualTo(BadCredentialsException.class);

    assertThat(unknownEmailExMessage)
        .as(
            "Unknown email and wrong password must produce the same exception message (no distinguishing info)")
        .isEqualTo(wrongPasswordExMessage);
  }

  @Test
  void login_unknownEmailException_doesNotRevealThatEmailIsUnknown() {
    when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());
    when(passwordEncoder.matches(anyString(), eq(DUMMY_HASH))).thenReturn(false);

    try {
      authService.login(new LoginRequest("nobody@example.com", TEST_PASSWORD), httpRequest);
    } catch (BadCredentialsException ex) {
      assertThat(ex.getMessage())
          .as("Exception message must not reveal that the email does not exist")
          .doesNotContainIgnoringCase("email")
          .doesNotContainIgnoringCase("user")
          .doesNotContainIgnoringCase("found")
          .doesNotContainIgnoringCase("exist");
      return;
    }

    org.junit.jupiter.api.Assertions.fail("Expected BadCredentialsException was not thrown");
  }
}
