package com.thedogs.modules.auth;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * AUTH-03: Nightly cleanup of expired and revoked refresh tokens.
 *
 * <p>Retains rows for 30 days after revocation/expiry for audit trail purposes before hard-
 * deleting. The cutoff window can be tuned by adjusting the cron expression without code changes if
 * moved to application.yml (TODO(ops): externalise cron and retention period).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenCleanupService {

  private final RefreshTokenRepository refreshTokenRepository;

  /**
   * Runs at 03:00 every night. Deletes tokens whose revoked_at or expires_at (if never revoked)
   * is older than 30 days.
   *
   * <p>TODO(ops): tune cleanup schedule and retention days via application.yml without code change.
   */
  @Scheduled(cron = "0 0 3 * * *")
  public void cleanupExpiredAndRevokedTokens() {
    Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
    refreshTokenRepository.deleteExpiredAndRevoked(cutoff);
    log.info("Refresh token cleanup completed, cutoff={}", cutoff);
  }
}
