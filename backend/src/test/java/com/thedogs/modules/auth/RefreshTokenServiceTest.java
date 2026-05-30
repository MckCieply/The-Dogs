package com.thedogs.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.thedogs.modules.auth.exception.InvalidRefreshTokenException;
import com.thedogs.modules.auth.exception.RefreshTokenExpiredException;
import com.thedogs.modules.auth.exception.RefreshTokenReusedException;
import com.thedogs.modules.auth.exception.RefreshTokenRevokedException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Mockito unit tests for RefreshTokenService. No Spring context — all collaborators are mocked.
 *
 * <p>Covers: rotateToken() happy path, all 4 exception branches, revokeFamily(), and the
 * concurrent-race assertion (two threads rotating the same token; one succeeds, one detects reuse).
 */
class RefreshTokenServiceTest {

  private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
  private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
  private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");

  private static final String OLD_TOKEN_VALUE = "old-token-plaintext-value";
  private static final String NEW_TOKEN_VALUE = "new-token-plaintext-value";

  private RefreshTokenRepository repository;
  private TokenService tokenService;
  private RefreshTokenService service;

  @BeforeEach
  void setUp() {
    repository = mock(RefreshTokenRepository.class);
    tokenService = mock(TokenService.class);
    service = new RefreshTokenService(repository, tokenService);

    // Default TTL stub used by rotateToken() when computing new expiry
    when(tokenService.getRefreshTokenTtlSeconds()).thenReturn(604800L);

    // rotateToken() uses a two-phase read strategy:
    //   Phase 1 — non-locking findByTokenHash() for early theft detection (no SELECT FOR UPDATE)
    //   Phase 2 — findByTokenHashForUpdate() with SELECT FOR UPDATE for safe rotation
    // Tests that call rotateToken() must stub BOTH methods so Phase 1 does not throw
    // InvalidRefreshTokenException before Phase 2 is reached.
    // Default: return empty so unexpected token hashes produce the expected error.
    // Individual tests override this default for the specific hash they exercise.
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — happy path: marks old as used, creates new with parentId
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withValidUnusedToken_marksOldTokenUsed() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(old));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);

    // The old token must have been saved with usedAt set
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository, times(2)).save(captor.capture());
    RefreshToken saved = captor.getAllValues().get(0);
    assertThat(saved.getUsedAt())
        .as("usedAt must be set on the old token after rotation")
        .isNotNull();
  }

  @Test
  void rotateToken_withValidUnusedToken_createsNewTokenWithParentId() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(old));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RefreshToken result = service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);

    assertThat(result.getParentId())
        .as("New token must link to the old token via parentId")
        .isEqualTo(TOKEN_ID);
  }

  @Test
  void rotateToken_withValidUnusedToken_newTokenInheritsFamilyId() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(old));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RefreshToken result = service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);

    assertThat(result.getFamilyId())
        .as("New token must inherit the same familyId as the rotated token")
        .isEqualTo(FAMILY_ID);
  }

  @Test
  void rotateToken_withValidUnusedToken_newTokenHashMatchesSha256OfNewValue() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(old));
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    RefreshToken result = service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);

    String expectedNewHash = RefreshTokenService.sha256Hex(NEW_TOKEN_VALUE);
    assertThat(result.getTokenHash())
        .as("New token must be stored as SHA-256 hash of the new value")
        .isEqualTo(expectedNewHash);
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — usedAt != null → RefreshTokenReusedException + revokeFamily called
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withAlreadyUsedToken_throwsRefreshTokenReusedException() {
    RefreshToken usedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    usedToken.setUsedAt(Instant.now().minusSeconds(10));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Phase 1 (non-locking) detects usedAt != null — Phase 2 (locking) is never reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(usedToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(usedToken));
    when(repository.findActiveByFamilyId(FAMILY_ID)).thenReturn(List.of());

    assertThatThrownBy(() -> service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE))
        .isInstanceOf(RefreshTokenReusedException.class);
  }

  @Test
  void rotateToken_withAlreadyUsedToken_callsRevokeFamilyBeforeThrowing() {
    RefreshToken usedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    usedToken.setUsedAt(Instant.now().minusSeconds(10));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);
    List<RefreshToken> family = List.of(usedToken);

    // Phase 1 (non-locking) detects usedAt != null — Phase 2 (locking) is never reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(usedToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(usedToken));
    when(repository.findActiveByFamilyId(FAMILY_ID)).thenReturn(family);
    when(repository.saveAll(any())).thenReturn(family);

    try {
      service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);
    } catch (RefreshTokenReusedException ignored) {
    }

    verify(repository).findActiveByFamilyId(FAMILY_ID);
    verify(repository).saveAll(any());
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — revokedAt != null → RefreshTokenRevokedException
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withRevokedToken_throwsRefreshTokenRevokedException() {
    RefreshToken revokedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    revokedToken.setRevokedAt(Instant.now().minusSeconds(30));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Phase 1: revokedToken has usedAt=null → passes Phase 1, proceeds to Phase 2 (locking read).
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(revokedToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(revokedToken));

    assertThatThrownBy(() -> service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE))
        .isInstanceOf(RefreshTokenRevokedException.class);
  }

  @Test
  void rotateToken_withRevokedToken_doesNotCallSaveOnOldToken() {
    RefreshToken revokedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    revokedToken.setRevokedAt(Instant.now().minusSeconds(30));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(revokedToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(revokedToken));

    try {
      service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);
    } catch (RefreshTokenRevokedException ignored) {
    }

    // No save() should be called — we should not modify a revoked token
    verify(repository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — expiresAt in past → RefreshTokenExpiredException
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withExpiredToken_throwsRefreshTokenExpiredException() {
    RefreshToken expiredToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    expiredToken.setExpiresAt(Instant.now().minusSeconds(60));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Phase 1: expiredToken has usedAt=null → passes Phase 1, proceeds to Phase 2 (locking read).
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(expiredToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(expiredToken));

    assertThatThrownBy(() -> service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE))
        .isInstanceOf(RefreshTokenExpiredException.class);
  }

  @Test
  void rotateToken_withExpiredToken_doesNotCallSaveOnOldToken() {
    RefreshToken expiredToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    expiredToken.setExpiresAt(Instant.now().minusSeconds(60));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(expiredToken));
    when(repository.findByTokenHashForUpdate(oldHash)).thenReturn(Optional.of(expiredToken));

    try {
      service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);
    } catch (RefreshTokenExpiredException ignored) {
    }

    verify(repository, never()).save(any());
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — no DB row → InvalidRefreshTokenException
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withUnknownToken_throwsInvalidRefreshTokenException() {
    String unknownHash = RefreshTokenService.sha256Hex("completely-unknown-token");
    when(repository.findByTokenHashForUpdate(unknownHash)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.rotateToken("completely-unknown-token", NEW_TOKEN_VALUE))
        .isInstanceOf(InvalidRefreshTokenException.class);
  }

  // ---------------------------------------------------------------------------
  // revokeFamily() — all active family members get revokedAt set
  // ---------------------------------------------------------------------------

  @Test
  void revokeFamily_setsRevokedAtOnAllActiveFamilyMembers() {
    RefreshToken t1 = buildActiveToken(UUID.randomUUID(), FAMILY_ID, USER_ID);
    RefreshToken t2 = buildActiveToken(UUID.randomUUID(), FAMILY_ID, USER_ID);
    List<RefreshToken> activeFamily = List.of(t1, t2);

    when(repository.findActiveByFamilyId(FAMILY_ID)).thenReturn(activeFamily);
    when(repository.saveAll(any())).thenReturn(activeFamily);

    service.revokeFamily(FAMILY_ID);

    assertThat(t1.getRevokedAt()).as("t1.revokedAt must be set").isNotNull();
    assertThat(t2.getRevokedAt()).as("t2.revokedAt must be set").isNotNull();
    verify(repository).saveAll(activeFamily);
  }

  @Test
  void revokeFamily_withNoActiveMembers_callsSaveAllWithEmptyList() {
    when(repository.findActiveByFamilyId(FAMILY_ID)).thenReturn(List.of());
    when(repository.saveAll(any())).thenReturn(List.of());

    service.revokeFamily(FAMILY_ID);

    verify(repository).saveAll(List.of());
  }

  // ---------------------------------------------------------------------------
  // Concurrent race simulation: two threads rotating same token concurrently.
  // Because this is a Mockito test (no real SELECT FOR UPDATE), we simulate
  // the race by using the first-writer-wins pattern: the stub tracks whether
  // usedAt has been set and makes the second call see the used token.
  //
  // This test verifies the service logic, not the DB locking. The real
  // DB-level concurrency is validated in AuthRefreshLogoutIT (end-to-end).
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_concurrentRaceOnSameToken_exactlyOneSucceedsAndOneThrowsReusedException()
      throws Exception {
    // Use a mutable container to simulate the SELECT FOR UPDATE serialisation:
    // the first thread that sets usedAt wins; the second thread sees usedAt != null.
    RefreshToken sharedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Each thread uses a distinct new token value to produce different hashes
    String newValue1 = "new-token-for-thread-1";
    String newValue2 = "new-token-for-thread-2";

    // Simulate the two-phase read race condition with separate call counters.
    //
    // Phase 1 (findByTokenHash — non-locking):
    //   Both threads see a fresh token here (before any rotation commits).
    // Phase 2 (findByTokenHashForUpdate — SELECT FOR UPDATE):
    //   Thread-1 is first through the lock and sees a fresh token.
    //   Thread-2 is second and sees the token as already used (thread-1 committed).
    //
    // This models: thread-1 acquires the lock first and rotates; thread-2 finds usedAt != null.
    AtomicInteger phase2CallCount = new AtomicInteger(0);

    // Phase 1: both threads see a fresh token (no lock contention yet)
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(sharedToken));

    // Phase 2: first call returns fresh; second call returns used (thread-1 committed first)
    when(repository.findByTokenHashForUpdate(oldHash))
        .thenAnswer(
            inv -> {
              int call = phase2CallCount.incrementAndGet();
              if (call == 1) {
                // First thread through the lock: token is still unused
                return Optional.of(sharedToken);
              } else {
                // Second thread through the lock: simulate first thread having committed rotation
                RefreshToken usedView = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
                usedView.setUsedAt(Instant.now().minusSeconds(1));
                return Optional.of(usedView);
              }
            });

    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(repository.findActiveByFamilyId(FAMILY_ID)).thenReturn(List.of(sharedToken));
    when(repository.saveAll(any())).thenReturn(List.of());

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger reuseExceptionCount = new AtomicInteger(0);

    CountDownLatch startLatch = new CountDownLatch(1);

    Runnable task1 =
        () -> {
          try {
            startLatch.await();
            service.rotateToken(OLD_TOKEN_VALUE, newValue1);
            successCount.incrementAndGet();
          } catch (RefreshTokenReusedException e) {
            reuseExceptionCount.incrementAndGet();
          } catch (Exception ignored) {
          }
        };

    Runnable task2 =
        () -> {
          try {
            startLatch.await();
            service.rotateToken(OLD_TOKEN_VALUE, newValue2);
            successCount.incrementAndGet();
          } catch (RefreshTokenReusedException e) {
            reuseExceptionCount.incrementAndGet();
          } catch (Exception ignored) {
          }
        };

    ExecutorService executor = Executors.newFixedThreadPool(2);
    Future<?> f1 = executor.submit(task1);
    Future<?> f2 = executor.submit(task2);

    startLatch.countDown(); // release both threads simultaneously

    f1.get();
    f2.get();
    executor.shutdown();

    assertThat(successCount.get())
        .as("Exactly one thread must succeed in rotating the token")
        .isEqualTo(1);
    assertThat(reuseExceptionCount.get())
        .as("Exactly one thread must receive RefreshTokenReusedException (theft detection)")
        .isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // sha256Hex() utility — sanity-check the static helper used throughout the service
  // ---------------------------------------------------------------------------

  @Test
  void sha256Hex_producesExactly64HexCharacters() {
    String hex = RefreshTokenService.sha256Hex("test-input");
    assertThat(hex).matches("[0-9a-f]{64}");
  }

  @Test
  void sha256Hex_producesDeterministicOutput() {
    String input = "some-token-value";
    assertThat(RefreshTokenService.sha256Hex(input))
        .isEqualTo(RefreshTokenService.sha256Hex(input));
  }

  @Test
  void sha256Hex_differentInputsProduceDifferentHashes() {
    assertThat(RefreshTokenService.sha256Hex("value-a"))
        .isNotEqualTo(RefreshTokenService.sha256Hex("value-b"));
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /**
   * Builds an active (not used, not revoked, not expired) RefreshToken with the given identifiers.
   * The tokenHash is set to the SHA-256 of {@code OLD_TOKEN_VALUE} for the primary test token, or a
   * random value for ancillary tokens.
   */
  private static RefreshToken buildActiveToken(UUID id, UUID familyId, UUID userId) {
    return RefreshToken.builder()
        .id(id)
        .familyId(familyId)
        .userId(userId)
        .tokenHash(RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE + id))
        .issuedAt(Instant.now().minusSeconds(5))
        .expiresAt(Instant.now().plusSeconds(604800))
        .build();
  }
}
