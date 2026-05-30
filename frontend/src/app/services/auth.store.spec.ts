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

  it('refresh re-throws when AuthService.refresh rejects', async () => {
    authServiceMock.refresh.mockRejectedValue(new Error('session expired'));

    await expect(store.refresh()).rejects.toThrow('session expired');
  });
});
