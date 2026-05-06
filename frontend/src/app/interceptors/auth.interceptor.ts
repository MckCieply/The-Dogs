import { inject } from '@angular/core';
import {
  HttpErrorResponse,
  HttpHandlerFn,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { AuthStore } from '../services/auth.store';

/**
 * Functional HTTP interceptor (Angular 15+ style).
 *
 * Responsibilities:
 *  1. Attach `Authorization: Bearer <token>` header when a token is in memory.
 *  2. On 401 response, attempt a single silent refresh via the cookie.
 *  3. If refresh succeeds, retry the original request once with the new token.
 *  4. If refresh fails, clear the token and propagate the error (guard redirects to /login).
 */
export const authInterceptor: HttpInterceptorFn = (
  req: HttpRequest<unknown>,
  next: HttpHandlerFn,
) => {
  const authStore = inject(AuthStore);

  const addBearer = (r: HttpRequest<unknown>, token: string | null) =>
    token
      ? r.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : r;

  const authReq = addBearer(req, authStore.getAccessToken());

  return next(authReq).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        // Don't loop on the refresh endpoint itself
        !req.url.includes('/auth/refresh') &&
        !req.url.includes('/auth/login')
      ) {
        // Attempt silent refresh — returns an Observable
        return from(authStore.refresh()).pipe(
          switchMap((newToken: string) => {
            const retryReq = addBearer(req, newToken);
            return next(retryReq);
          }),
          catchError((refreshError: unknown) => {
            // Refresh failed: clear state so the guard can redirect to /login
            authStore.clearToken();
            return throwError(() => refreshError);
          }),
        );
      }
      return throwError(() => error);
    }),
  );
};
