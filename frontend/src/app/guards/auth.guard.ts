import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from '../services/auth.store';

/**
 * Protects routes that require authentication.
 *
 * The access token lives in memory only (ADR-0003), so after a hard reload it is always gone —
 * but the HttpOnly refresh cookie usually is not. Before redirecting to /login, attempt one
 * silent refresh: if the cookie is valid the whole session (token + identity) is restored and
 * navigation proceeds; only when the refresh fails does the user land on the login page.
 */
export const authGuard: CanActivateFn = async () => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (authStore.isAuthenticated()) {
    return true;
  }

  try {
    await authStore.refresh();
    return true;
  } catch {
    return router.createUrlTree(['/login']);
  }
};
