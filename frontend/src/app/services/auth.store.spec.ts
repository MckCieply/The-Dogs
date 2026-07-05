import { TestBed } from '@angular/core/testing';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { AuthStore } from './auth.store';
import { AuthService } from './auth.service';
import { AuthUser, LoginResponse, RefreshResponse } from '../models/auth.models';

/**
 * Vitest unit tests for AuthStore (NgRx Signal Store).
 *
 * Covers every withMethods action and every withComputed derivation.
 * AuthService is always mocked — no HTTP calls are made.
 */
describe('AuthStore', () => {
  let store: InstanceType<typeof AuthStore>;
  let authServiceMock: {
    login: ReturnType<typeof vi.fn>;
    logout: ReturnType<typeof vi.fn>;
    refresh: ReturnType<typeof vi.fn>;
  };

  const TEST_USER: AuthUser = { email: 'trainer@example.com', roles: ['ROLE_TRAINER'] };
  const TEST_TOKEN = 'header.payload.signature';

  const LOGIN_RESPONSE: LoginResponse = {
    accessToken: TEST_TOKEN,
    tokenType: 'Bearer',
    expiresIn: 900,
    email: 'trainer@example.com',
    roles: ['ROLE_TRAINER'],
  };

  const REFRESH_RESPONSE: RefreshResponse = {
    accessToken: 'refreshed-token',
    tokenType: 'Bearer',
    expiresIn: 900,
  };

  beforeEach(() => {
    authServiceMock = {
      login: vi.fn(),
      logout: vi.fn(),
      refresh: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        AuthStore,
        { provide: AuthService, useValue: authServiceMock },
      ],
    });

    store = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  // ---------------------------------------------------------------------------
  // Initial state
  // ---------------------------------------------------------------------------

  it('has null accessToken and user in initial state', () => {
    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();
  });

  it('has loading=false and error=null in initial state', () => {
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // withComputed: isAuthenticated
  // ---------------------------------------------------------------------------

  it('isAuthenticated returns false when accessToken is null', () => {
    expect(store.isAuthenticated()).toBe(false);
  });

  it('isAuthenticated returns true after setToken', () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    expect(store.isAuthenticated()).toBe(true);
  });

  it('isAuthenticated returns false after clearToken', () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    store.clearToken();
    expect(store.isAuthenticated()).toBe(false);
  });

  // ---------------------------------------------------------------------------
  // withComputed: currentUser
  // ---------------------------------------------------------------------------

  it('currentUser returns null before login', () => {
    expect(store.currentUser()).toBeNull();
  });

  it('currentUser returns user after setToken', () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    expect(store.currentUser()).toEqual(TEST_USER);
  });

  // ---------------------------------------------------------------------------
  // withComputed: isLoading
  // ---------------------------------------------------------------------------

  it('isLoading reflects loading signal', () => {
    store.setLoading(true);
    expect(store.isLoading()).toBe(true);
    store.setLoading(false);
    expect(store.isLoading()).toBe(false);
  });

  // ---------------------------------------------------------------------------
  // withComputed: authError
  // ---------------------------------------------------------------------------

  it('authError returns null initially', () => {
    expect(store.authError()).toBeNull();
  });

  it('authError returns the set error string', () => {
    store.setError('Something went wrong');
    expect(store.authError()).toBe('Something went wrong');
  });

  it('authError returns null after setError(null)', () => {
    store.setError('Some error');
    store.setError(null);
    expect(store.authError()).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // setToken action
  // ---------------------------------------------------------------------------

  it('setToken sets accessToken and user and clears error', () => {
    store.setError('pre-existing error');
    store.setToken(TEST_TOKEN, TEST_USER);
    expect(store.accessToken()).toBe(TEST_TOKEN);
    expect(store.user()).toEqual(TEST_USER);
    expect(store.error()).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // clearToken action
  // ---------------------------------------------------------------------------

  it('clearToken nullifies accessToken and user', () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    store.clearToken();
    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();
  });

  // ---------------------------------------------------------------------------
  // getAccessToken action
  // ---------------------------------------------------------------------------

  it('getAccessToken returns null when not set', () => {
    expect(store.getAccessToken()).toBeNull();
  });

  it('getAccessToken returns the current token', () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    expect(store.getAccessToken()).toBe(TEST_TOKEN);
  });

  // ---------------------------------------------------------------------------
  // login() action — happy path
  // ---------------------------------------------------------------------------

  it('login sets accessToken, user, loading=false, error=null on success', async () => {
    authServiceMock.login.mockResolvedValue(LOGIN_RESPONSE);

    await store.login('trainer@example.com', 'secret');

    expect(store.accessToken()).toBe(TEST_TOKEN);
    expect(store.user()).toEqual({ email: 'trainer@example.com', roles: ['ROLE_TRAINER'] });
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('login calls AuthService.login with correct credentials', async () => {
    authServiceMock.login.mockResolvedValue(LOGIN_RESPONSE);

    await store.login('trainer@example.com', 'secret');

    expect(authServiceMock.login).toHaveBeenCalledWith('trainer@example.com', 'secret');
    expect(authServiceMock.login).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------------------
  // login() action — failure path
  // ---------------------------------------------------------------------------

  it('login sets error and loading=false on AuthService rejection', async () => {
    authServiceMock.login.mockRejectedValue(new Error('Invalid credentials'));

    await expect(store.login('bad@example.com', 'wrong')).rejects.toBeDefined();

    expect(store.loading()).toBe(false);
    expect(store.error()).toBe('Invalid credentials');
    expect(store.accessToken()).toBeNull();
  });

  it('login re-throws the error so callers can react', async () => {
    authServiceMock.login.mockRejectedValue(new Error('network error'));

    await expect(store.login('user@example.com', 'pass')).rejects.toThrow('network error');
  });

  // ---------------------------------------------------------------------------
  // logout() action
  // ---------------------------------------------------------------------------

  it('logout clears accessToken, user, and error regardless of backend response', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    authServiceMock.logout.mockResolvedValue(undefined);

    await store.logout();

    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();
  });

  it('logout calls AuthService.logout once', async () => {
    authServiceMock.logout.mockResolvedValue(undefined);

    await store.logout();

    expect(authServiceMock.logout).toHaveBeenCalledTimes(1);
  });

  it('logout clears state even when AuthService.logout rejects', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    authServiceMock.logout.mockRejectedValue(new Error('network error'));

    // The store uses finally{} — state must be cleared even on error
    await store.logout();

    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();
  });

  it('logout with no active session leaves state cleared and still delegates to AuthService', async () => {
    // Precondition: store is in its initial state — no token, no user.
    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();

    authServiceMock.logout.mockResolvedValue(undefined);

    await store.logout();

    // State must remain cleared after the call.
    expect(store.accessToken()).toBeNull();
    expect(store.user()).toBeNull();
    expect(store.loading()).toBe(false);
    expect(store.error()).toBeNull();

    // The store always delegates to AuthService.logout regardless of whether a
    // session was active — it cannot know whether the browser still holds a valid
    // HttpOnly cookie, so the backend call is always made.
    expect(authServiceMock.logout).toHaveBeenCalledTimes(1);
  });

  // ---------------------------------------------------------------------------
  // refresh() action
  // ---------------------------------------------------------------------------

  it('refresh updates accessToken with the new token from AuthService', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);
    authServiceMock.refresh.mockResolvedValue(REFRESH_RESPONSE);

    const newToken = await store.refresh();

    expect(newToken).toBe('refreshed-token');
    expect(store.accessToken()).toBe('refreshed-token');
  });

  it('refresh calls AuthService.refresh once', async () => {
    authServiceMock.refresh.mockResolvedValue(REFRESH_RESPONSE);

    await store.refresh();

    expect(authServiceMock.refresh).toHaveBeenCalledTimes(1);
  });

  it('concurrent refresh calls share a single HTTP refresh (silent-refresh queue)', async () => {
    // AUTH-06: refresh tokens rotate on use — a second concurrent call presenting the
    // already-consumed token would trip theft detection and revoke the whole session.
    let release!: (value: RefreshResponse) => void;
    authServiceMock.refresh.mockReturnValue(
      new Promise<RefreshResponse>((r) => (release = r)),
    );

    const first = store.refresh();
    const second = store.refresh();
    release(REFRESH_RESPONSE);

    await expect(first).resolves.toBe('refreshed-token');
    await expect(second).resolves.toBe('refreshed-token');
    expect(authServiceMock.refresh).toHaveBeenCalledTimes(1);
  });

  it('a new refresh after the previous one settles performs a fresh HTTP call', async () => {
    authServiceMock.refresh.mockResolvedValue(REFRESH_RESPONSE);

    await store.refresh();
    await store.refresh();

    expect(authServiceMock.refresh).toHaveBeenCalledTimes(2);
  });

  it('refresh re-throws when AuthService.refresh rejects', async () => {
    authServiceMock.refresh.mockRejectedValue(new Error('session expired'));

    await expect(store.refresh()).rejects.toThrow('session expired');
  });

  // ---------------------------------------------------------------------------
  // refresh() action — 401 error-code paths (theft detection + revocation)
  //
  // NOTE FOR IMPLEMENTER/REVIEWER: the current refresh() implementation has no
  // catch block, so it re-throws without clearing state or setting an error.
  // These tests document that behaviour. If the spec requires the store to clear
  // the session on receipt of refresh_revoked / refresh_reused (so the UI can
  // redirect to /login immediately without a manual store.clearToken() call),
  // add a catch block to refresh() and update these tests accordingly.
  // ---------------------------------------------------------------------------

  it('refresh() with refresh_revoked 401 re-throws and leaves accessToken unchanged', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);

    const revokedError = Object.assign(new Error('refresh_revoked'), {
      status: 401,
      error: { errors: [{ code: 'refresh_revoked' }] },
    });
    authServiceMock.refresh.mockRejectedValue(revokedError);

    await expect(store.refresh()).rejects.toMatchObject({ message: 'refresh_revoked' });

    // Current implementation: no catch block — state is not cleared on refresh failure.
    // The in-memory token remains until the caller (e.g. the HTTP interceptor) explicitly
    // calls store.clearToken() after catching the error.
    expect(store.accessToken()).toBe(TEST_TOKEN);
    expect(store.user()).toEqual(TEST_USER);
  });

  it('refresh() with refresh_revoked 401 does not set an error on the store', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);

    const revokedError = Object.assign(new Error('refresh_revoked'), {
      status: 401,
      error: { errors: [{ code: 'refresh_revoked' }] },
    });
    authServiceMock.refresh.mockRejectedValue(revokedError);

    await expect(store.refresh()).rejects.toBeDefined();

    // Current implementation: refresh() has no catch, so error signal is never set.
    expect(store.error()).toBeNull();
  });

  it('refresh() with refresh_reused 401 re-throws and leaves accessToken unchanged', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);

    const reusedError = Object.assign(new Error('refresh_reused'), {
      status: 401,
      error: { errors: [{ code: 'refresh_reused' }] },
    });
    authServiceMock.refresh.mockRejectedValue(reusedError);

    await expect(store.refresh()).rejects.toMatchObject({ message: 'refresh_reused' });

    // Current implementation: theft-detection response does not automatically clear
    // the in-memory token — caller is responsible for calling store.clearToken().
    expect(store.accessToken()).toBe(TEST_TOKEN);
    expect(store.user()).toEqual(TEST_USER);
  });

  it('refresh() with refresh_reused 401 does not set an error on the store', async () => {
    store.setToken(TEST_TOKEN, TEST_USER);

    const reusedError = Object.assign(new Error('refresh_reused'), {
      status: 401,
      error: { errors: [{ code: 'refresh_reused' }] },
    });
    authServiceMock.refresh.mockRejectedValue(reusedError);

    await expect(store.refresh()).rejects.toBeDefined();

    expect(store.error()).toBeNull();
  });
});
