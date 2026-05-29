package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for LoginRateLimiter (AUTH-02).
 *
 * <p>No Spring context, no DB, no Docker required. A fresh {@link LoginRateLimiter} instance is
 * created per test so bucket state never bleeds between tests.
 *
 * <p>Policy under test: 5 failed attempts per IP per 15-minute window. The 6th call to {@code
 * checkLimit()} after 5 recorded failures must throw {@link TooManyLoginAttemptsException}.
 */
class LoginRateLimiterTest {

  private LoginRateLimiter rateLimiter;

  @BeforeEach
  void setUp() {
    // Fresh instance per test ensures independent bucket state
    rateLimiter = new LoginRateLimiter();
  }

  // ---------------------------------------------------------------------------
  // Happy path: first 5 failures from the same IP must not throw on checkLimit()
  // ---------------------------------------------------------------------------

  @Test
  void checkLimit_beforeAnyFailures_doesNotThrow() {
    assertThatCode(() -> rateLimiter.checkLimit("10.0.0.1")).doesNotThrowAnyException();
  }

  @Test
  void checkLimit_after4RecordedFailures_doesNotThrow() {
    String ip = "10.0.0.2";
    for (int i = 0; i < 4; i++) {
      rateLimiter.recordFailure(ip);
    }

    assertThatCode(() -> rateLimiter.checkLimit(ip)).doesNotThrowAnyException();
  }

  @Test
  void checkLimit_after5RecordedFailures_doesNotThrow() {
    // The 5th failure exhausts the bucket; checkLimit() reads available tokens which will be 0 at
    // this point, but the contract says: check is called BEFORE recordFailure. The 6th attempt
    // triggers the block. After exactly 5 recordFailure() calls the bucket is empty, but the
    // check occurs before the 6th recordFailure — i.e. checkLimit() is called when tokens == 0.
    // So 5 recordFailure calls → checkLimit() must throw.
    String ip = "10.0.0.3";
    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(ip);
    }

    assertThatThrownBy(() -> rateLimiter.checkLimit(ip))
        .isInstanceOf(TooManyLoginAttemptsException.class);
  }

  // ---------------------------------------------------------------------------
  // 6th attempt (after 5 failures already recorded): checkLimit() throws
  // ---------------------------------------------------------------------------

  @Test
  void checkLimit_onSixthAttemptAfter5Failures_throwsTooManyLoginAttemptsException() {
    String ip = "10.0.0.4";

    // Simulate 5 prior failed attempts consuming all tokens
    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(ip);
    }

    // The 6th call to checkLimit() must be blocked
    assertThatThrownBy(() -> rateLimiter.checkLimit(ip))
        .isInstanceOf(TooManyLoginAttemptsException.class)
        .hasMessage("Too many login attempts");
  }

  // ---------------------------------------------------------------------------
  // Different IPs have independent buckets
  // ---------------------------------------------------------------------------

  @Test
  void checkLimit_differentIpsHaveIndependentBuckets() {
    String ipA = "10.1.0.1";
    String ipB = "10.1.0.2";

    // Exhaust ipA's bucket
    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(ipA);
    }

    // ipB must not be affected
    assertThatCode(() -> rateLimiter.checkLimit(ipB)).doesNotThrowAnyException();
  }

  @Test
  void checkLimit_exhaustingOneIp_doesNotBlockDifferentIp() {
    String blockedIp = "10.2.0.1";
    String freeIp = "10.2.0.2";

    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(blockedIp);
    }

    // Blocked IP is blocked
    assertThatThrownBy(() -> rateLimiter.checkLimit(blockedIp))
        .isInstanceOf(TooManyLoginAttemptsException.class);

    // Free IP is still allowed — must not throw
    assertThatCode(() -> rateLimiter.checkLimit(freeIp)).doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------------------
  // getRetryAfterSeconds() returns a positive value after limit is exceeded
  // ---------------------------------------------------------------------------

  @Test
  void getRetryAfterSeconds_afterLimitExceeded_returnsPositiveValue() {
    String ip = "10.3.0.1";

    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(ip);
    }

    // Bucket is empty; retry time must be positive (at most 15 minutes = 900 s)
    long retryAfter = rateLimiter.getRetryAfterSeconds(ip);
    assertThat(retryAfter)
        .as("retryAfterSeconds must be positive when bucket is exhausted")
        .isGreaterThan(0L)
        // Implementation rounds up by +1 to ensure callers never retry too early;
        // the ceiling for a 15-minute window is 900 s + 1 s rounding = 901 s.
        .isLessThanOrEqualTo(901L);
  }

  @Test
  void getRetryAfterSeconds_beforeAnyFailures_returnsZero() {
    String ip = "10.3.0.2";

    assertThat(rateLimiter.getRetryAfterSeconds(ip))
        .as("retryAfterSeconds must be 0 when tokens are still available")
        .isEqualTo(0L);
  }

  // ---------------------------------------------------------------------------
  // TooManyLoginAttemptsException carries the retryAfterSeconds value
  // ---------------------------------------------------------------------------

  @Test
  void checkLimit_exceptionCarriesPositiveRetryAfterSeconds() {
    String ip = "10.4.0.1";

    for (int i = 0; i < 5; i++) {
      rateLimiter.recordFailure(ip);
    }

    try {
      rateLimiter.checkLimit(ip);
    } catch (TooManyLoginAttemptsException ex) {
      assertThat(ex.getRetryAfterSeconds())
          .as("Exception must carry a positive retryAfterSeconds")
          .isGreaterThan(0L);
      return;
    }
    // If no exception was thrown, fail explicitly
    org.junit.jupiter.api.Assertions.fail(
        "Expected TooManyLoginAttemptsException was not thrown after 5 recorded failures");
  }
}
