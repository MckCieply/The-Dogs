package com.thedogs.modules.auth;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.EstimationProbe;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * In-memory rate limiter for failed login attempts, keyed by client IP address.
 *
 * <p>Policy: 5 failed attempts per 15 minutes, refilled all-at-once after the interval (intervally,
 * not gradually). An IP that exhausts all tokens is blocked until the 15-minute window resets.
 *
 * <p>NOTE: in-memory single-node only — replace with Redis/Postgres shared store for multi-instance
 * (AUTH-03+)
 */
@Service
public class LoginRateLimiter {

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration WINDOW = Duration.ofMinutes(15);

  // Keyed by raw IP string from IpAddressExtractor
  private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

  private Bucket bucketFor(String ip) {
    return buckets.computeIfAbsent(ip, key -> buildBucket());
  }

  private static Bucket buildBucket() {
    // Intervally refill: all MAX_ATTEMPTS tokens are restored together after WINDOW elapses,
    // not gradually. This matches the "5 failures per 15 minutes" intent.
    Bandwidth limit =
        Bandwidth.builder().capacity(MAX_ATTEMPTS).refillIntervally(MAX_ATTEMPTS, WINDOW).build();
    return Bucket.builder().addLimit(limit).build();
  }

  /**
   * Checks whether the given IP is currently rate-limited (i.e. has zero tokens remaining).
   *
   * @param ip raw IP string
   * @throws TooManyLoginAttemptsException if no tokens are available
   */
  public void checkLimit(String ip) {
    Bucket bucket = bucketFor(ip);
    if (bucket.getAvailableTokens() <= 0) {
      throw new TooManyLoginAttemptsException(getRetryAfterSeconds(ip));
    }
  }

  /**
   * Records a failed login attempt for the given IP by consuming one token.
   *
   * @param ip raw IP string
   */
  public void recordFailure(String ip) {
    bucketFor(ip).tryConsume(1);
  }

  /**
   * Clears all rate-limit buckets. Intended only for use in integration test {@code @BeforeEach}
   * setup to reset shared in-memory state between test methods that share the same Spring
   * application context.
   *
   * <p>Package-private so it is not accidentally called from production code.
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
    // Probe how long until 1 token is available without consuming it
    EstimationProbe probe = bucket.estimateAbilityToConsume(1);
    long nanosToWait = probe.getNanosToWaitForRefill();
    return (nanosToWait / 1_000_000_000L) + 1L; // round up to nearest second
  }
}
