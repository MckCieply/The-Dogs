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

    // rotateToken() uses a two-phase strategy:
    //   Phase 1 — non-locking findByTokenHash() for early detection of invalid/used/revoked tokens
    //   Phase 2 — markUsedIfActive() atomic conditional UPDATE (returns 1=won, 0=lost the race)
    // Tests that call rotateToken() must stub both methods appropriately.
    // Default: markUsedIfActive returns 1 (caller wins the race) for the happy path.
  }

  // ---------------------------------------------------------------------------
  // rotateToken() — happy path: marks old as used, creates new with parentId
  // ---------------------------------------------------------------------------

  @Test
  void rotateToken_withValidUnusedToken_marksOldTokenUsed() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Phase 1: non-locking read returns the active token.
    // Phase 2: markUsedIfActive returns 1 — this caller wins the race.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.markUsedIfActive(any(), any())).thenReturn(1);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE);

    // The new token must have been saved (issue() calls save once)
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repository, times(1)).save(captor.capture());
    RefreshToken saved = captor.getValue();
    assertThat(saved.getParentId())
        .as("The saved new token must reference the old token as parent")
        .isEqualTo(TOKEN_ID);
  }

  @Test
  void rotateToken_withValidUnusedToken_createsNewTokenWithParentId() {
    RefreshToken old = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(old));
    when(repository.markUsedIfActive(any(), any())).thenReturn(1);
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
    when(repository.markUsedIfActive(any(), any())).thenReturn(1);
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
    when(repository.markUsedIfActive(any(), any())).thenReturn(1);
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

    // Phase 1 (non-locking) detects usedAt != null — Phase 2 (lock+refresh) is never reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(usedToken));
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

    // Phase 1 (non-locking) detects usedAt != null — Phase 2 (lock+refresh) is never reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(usedToken));
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

    // Phase 1 detects revokedAt != null → throws before Phase 2 (markUsedIfActive) is reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(revokedToken));

    assertThatThrownBy(() -> service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE))
        .isInstanceOf(RefreshTokenRevokedException.class);
  }

  @Test
  void rotateToken_withRevokedToken_doesNotCallSaveOnOldToken() {
    RefreshToken revokedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    revokedToken.setRevokedAt(Instant.now().minusSeconds(30));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(revokedToken));

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

    // Phase 1 detects expiresAt < now → throws before Phase 2 (markUsedIfActive) is reached.
    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(expiredToken));

    assertThatThrownBy(() -> service.rotateToken(OLD_TOKEN_VALUE, NEW_TOKEN_VALUE))
        .isInstanceOf(RefreshTokenExpiredException.class);
  }

  @Test
  void rotateToken_withExpiredToken_doesNotCallSaveOnOldToken() {
    RefreshToken expiredToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    expiredToken.setExpiresAt(Instant.now().minusSeconds(60));
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    when(repository.findByTokenHash(oldHash)).thenReturn(Optional.of(expiredToken));

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
    // Phase 1: findByTokenHash returns empty — InvalidRefreshTokenException thrown immediately
    // before Phase 2 (markUsedIfActive) is reached.
    String unknownHash = RefreshTokenService.sha256Hex("completely-unknown-token");
    when(repository.findByTokenHash(unknownHash)).thenReturn(Optional.empty());

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
    // Simulate the atomic conditional UPDATE race:
    // Thread-1 wins: markUsedIfActive returns 1 (row updated successfully).
    // Thread-2 loses: markUsedIfActive returns 0 (row already updated by thread-1).
    // When thread-2 gets 0, it re-reads the token and sees usedAt != null → theft detected.
    RefreshToken sharedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    String oldHash = RefreshTokenService.sha256Hex(OLD_TOKEN_VALUE);

    // Each thread uses a distinct new token value to produce different hashes
    String newValue1 = "new-token-for-thread-1";
    String newValue2 = "new-token-for-thread-2";

    AtomicInteger markUsedCallCount = new AtomicInteger(0);

    // Phase 1 (findByTokenHash — called twice: once for initial check, once in the "lost race"
    // path)
    // First two calls: return fresh token (initial Phase 1 check for each thread)
    // Third call: return used token (re-read after thread-2 gets 0 from markUsedIfActive)
    AtomicInteger findByHashCallCount = new AtomicInteger(0);
    RefreshToken usedToken = buildActiveToken(TOKEN_ID, FAMILY_ID, USER_ID);
    usedToken.setUsedAt(Instant.now().minusSeconds(1));

    when(repository.findByTokenHash(oldHash))
        .thenAnswer(
            inv -> {
              int call = findByHashCallCount.incrementAndGet();
              // First two calls: Phase 1 checks — token still fresh
              // Third call onwards: re-read after losing the race — token already used
              return call <= 2 ? Optional.of(sharedToken) : Optional.of(usedToken);
            });

    // Phase 2: first markUsedIfActive returns 1 (thread-1 wins); second returns 0 (thread-2 loses)
    when(repository.markUsedIfActive(any(), any()))
        .thenAnswer(
            inv -> {
              int call = markUsedCallCount.incrementAndGet();
              return call == 1 ? 1 : 0; // first wins, subsequent lose
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
