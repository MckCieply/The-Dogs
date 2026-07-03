package com.thedogs.modules.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory rate limiting for the password-reset flow (AUTH-05).
 *
 * <p>Two independent budgets:
 *
 * <ul>
 *   <li>forgot-password: 3 requests per <em>email hash</em> per hour. Keyed by the SHA-256 of the
 *       lowercased email so the bucket exists (and throttles identically) for non-existent emails —
 *       part of the anti-enumeration posture (AC-11).
 *   <li>reset-password: 10 attempts per IP per hour, consumed on every attempt (valid or not) to
 *       slow token brute-forcing.
 * </ul>
 *
 * <p>NOTE: in-memory single-node only — same multi-instance caveat as {@link LoginRateLimiter}.
 */
@Service
public class PasswordResetRateLimiter {

  private static final int MAX_FORGOT_PER_EMAIL = 3;
  private static final int MAX_RESET_PER_IP = 10;
  private static final Duration WINDOW = Duration.ofHours(1);

  private final ConcurrentHashMap<String, Bucket> forgotBuckets = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Bucket> resetBuckets = new ConcurrentHashMap<>();

  private static Bucket buildBucket(int capacity) {
    Bandwidth limit =
        Bandwidth.builder().capacity(capacity).refillIntervally(capacity, WINDOW).build();
    return Bucket.builder().addLimit(limit).build();
  }

  /**
   * Consumes one forgot-password token for the given email hash, throwing when the hourly budget is
   * exhausted (AC-11).
   */
  public void recordForgotAttempt(String emailHash) {
    Bucket bucket =
        forgotBuckets.computeIfAbsent(emailHash, k -> buildBucket(MAX_FORGOT_PER_EMAIL));
    if (!bucket.tryConsume(1)) {
      throw new TooManyLoginAttemptsException(retryAfterSeconds(bucket));
    }
  }

  /** Consumes one reset-password attempt for the given IP, throwing when exhausted. */
  public void recordResetAttempt(String ip) {
    Bucket bucket = resetBuckets.computeIfAbsent(ip, k -> buildBucket(MAX_RESET_PER_IP));
    if (!bucket.tryConsume(1)) {
      throw new TooManyLoginAttemptsException(retryAfterSeconds(bucket));
    }
  }

  private static long retryAfterSeconds(Bucket bucket) {
    EstimationProbe probe = bucket.estimateAbilityToConsume(1);
    return (probe.getNanosToWaitForRefill() / 1_000_000_000L) + 1L;
  }

  /** Clears all buckets. Integration-test use only — package-private on purpose. */
  void clearAll() {
    forgotBuckets.clear();
    resetBuckets.clear();
  }
}
