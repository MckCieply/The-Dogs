package com.thedogs.modules.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory rate limiter for account registration, keyed by client IP (AUTH-04, AC-7).
 *
 * <p>Policy: 3 successful registrations per hour per IP, refilled all-at-once after the window.
 * Tokens are consumed only on successful registration ({@link #recordRegistration}) — a legitimate
 * user fumbling validation is not locked out, while account-creation spam is capped.
 *
 * <p>NOTE: in-memory single-node only — same multi-instance caveat as {@link LoginRateLimiter}.
 */
@Service
public class RegistrationRateLimiter {

  private static final int MAX_REGISTRATIONS = 3;
  private static final Duration WINDOW = Duration.ofHours(1);

  // Keyed by raw IP string from IpAddressExtractor
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  private Bucket bucketFor(String ip) {
    return buckets.computeIfAbsent(ip, key -> buildBucket());
  }

  private static Bucket buildBucket() {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(MAX_REGISTRATIONS)
            .refillIntervally(MAX_REGISTRATIONS, WINDOW)
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  /**
   * Checks whether the given IP has registration tokens remaining.
   *
   * @param ip raw IP string
   * @throws TooManyLoginAttemptsException if the hourly registration budget is exhausted (mapped to
   *     429 {@code too_many_attempts} + {@code Retry-After} by the global handler)
   */
  public void checkLimit(String ip) {
    Bucket bucket = bucketFor(ip);
    if (bucket.getAvailableTokens() <= 0) {
      throw new TooManyLoginAttemptsException(getRetryAfterSeconds(ip));
    }
  }

  /**
   * Records a successful registration for the given IP by consuming one token.
   *
   * @param ip raw IP string
   */
  public void recordRegistration(String ip) {
    bucketFor(ip).tryConsume(1);
  }

  /**
   * Clears all rate-limit buckets. Intended only for integration test {@code @BeforeEach} setup —
   * package-private so it is not accidentally called from production code.
   */
  void clearAll() {
    buckets.clear();
  }

  /**
   * Returns the number of seconds until the next token becomes available for the given IP.
   *
   * @param ip raw IP string
   * @return seconds until refill, or 0 if tokens are already available
   */
  public long getRetryAfterSeconds(String ip) {
    Bucket bucket = bucketFor(ip);
    if (bucket.getAvailableTokens() > 0) {
      return 0L;
    }
    EstimationProbe probe = bucket.estimateAbilityToConsume(1);
    long nanosToWait = probe.getNanosToWaitForRefill();
    return (nanosToWait / 1_000_000_000L) + 1L; // round up to nearest second
  }
}
