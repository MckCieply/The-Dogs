import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthStore } from '../services/auth.store';

/**
 * Protects routes that require authentication.
 * Redirects to /login when no access token is in memory.
 *
 * Note: After a hard refresh the token is lost (by design — ADR-0003).
 * A future enhancement may attempt a silent refresh before redirecting.
 */
export const authGuard: CanActivateFn = () => {
  const authStore = inject(AuthStore);
  const router = inject(Router);

  if (authStore.isAuthenticated()) {
    return true;
  }

  return router.createUrlTree(['/login']);
};
