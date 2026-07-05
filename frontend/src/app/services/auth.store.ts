import { computed, inject } from '@angular/core';
import {
  patchState,
  signalStore,
  withComputed,
  withHooks,
  withMethods,
  withState,
} from '@ngrx/signals';
import { AuthUser } from '../models/auth.models';
import { AuthService } from './auth.service';

interface AuthState {
  user: AuthUser | null;
  /** Access token lives in memory only — never persisted to storage. */
  accessToken: string | null;
  loading: boolean;
  error: string | null;
}

const initialState: AuthState = {
  user: null,
  accessToken: null,
  loading: false,
  error: null,
};

export const AuthStore = signalStore(
  { providedIn: 'root' },
  withState(initialState),
  withComputed((store) => ({
    isAuthenticated: computed(() => store.accessToken() !== null),
    currentUser: computed(() => store.user()),
    isLoading: computed(() => store.loading()),
    authError: computed(() => store.error()),
  })),
  withMethods((store, authService = inject(AuthService)) => {
    /**
     * Single in-flight refresh shared by all callers (AUTH-06 silent-refresh queue).
     * Refresh tokens rotate on every use: if two requests racing the same 401 each
     * called the endpoint, the second would present the already-consumed token and
     * trip the backend's theft detection — revoking the entire session family.
     */
    let refreshInFlight: Promise<string> | null = null;

    return {
    setToken(token: string, user: AuthUser): void {
      patchState(store, { accessToken: token, user, error: null });
    },

    clearToken(): void {
      patchState(store, { accessToken: null, user: null });
    },

    setLoading(loading: boolean): void {
      patchState(store, { loading });
    },

    setError(error: string | null): void {
      patchState(store, { error });
    },

    /** Returns the current access token; callers do not hold a reference longer than needed. */
    getAccessToken(): string | null {
      return store.accessToken();
    },

    async login(email: string, password: string): Promise<void> {
      patchState(store, { loading: true, error: null });
      try {
        const response = await authService.login(email, password);
        patchState(store, {
          accessToken: response.accessToken,
          user: {
            email: response.email,
            displayName: response.displayName,
            roles: response.roles,
          },
          loading: false,
          error: null,
        });
      } catch (err: unknown) {
        const message =
          err instanceof Error ? err.message : 'Login failed. Please try again.';
        patchState(store, { loading: false, error: message });
        throw err;
      }
    },

    async logout(): Promise<void> {
      patchState(store, { loading: true });
      try {
        await authService.logout();
      } catch {
        // Logout is best-effort: even if the backend is unreachable we clear local auth state.
        // The server-side refresh-token will expire naturally.
      } finally {
        patchState(store, {
          accessToken: null,
          user: null,
          loading: false,
          error: null,
        });
      }
    },

    async refresh(): Promise<string> {
      refreshInFlight ??= authService
        .refresh()
        .then((response) => {
          patchState(store, {
            accessToken: response.accessToken,
            // The refresh response carries identity so a hard reload (memory-only
            // token lost, cookie intact) restores the full session, not just the token.
            user: {
              email: response.email,
              displayName: response.displayName,
              roles: response.roles,
            },
          });
          return response.accessToken;
        })
        .finally(() => {
          refreshInFlight = null;
        });
      return refreshInFlight;
    },
    };
  }),
  withHooks({
    onInit() {
      // No token hydration from storage — per ADR-0003, access tokens are in memory only.
    },
  }),
);
