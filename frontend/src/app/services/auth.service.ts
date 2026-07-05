import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  LoginRequest,
  LoginResponse,
  RefreshResponse,
  RegisterRequest,
} from '../models/auth.models';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly BASE = '/api/v1/auth';

  /**
   * POST /api/v1/auth/login
   * Credentials are transmitted; the backend sets an HttpOnly refresh-token cookie.
   * The returned access token must be stored in memory only (ADR-0003).
   */
  login(email: string, password: string): Promise<LoginResponse> {
    const body: LoginRequest = { email, password };
    return firstValueFrom(
      this.http.post<LoginResponse>(`${this.BASE}/login`, body, {
        withCredentials: true, // needed so the browser stores the HttpOnly cookie
      }),
    );
  }

  /**
   * POST /api/v1/auth/register (AUTH-07)
   * Same response contract as login: access token in the body, HttpOnly
   * refresh-token cookie set by the backend — the new user is signed in
   * immediately. The password passes straight through and is never stored.
   */
  register(
    email: string,
    password: string,
    displayName: string,
  ): Promise<LoginResponse> {
    const body: RegisterRequest = { email, password, displayName };
    return firstValueFrom(
      this.http.post<LoginResponse>(`${this.BASE}/register`, body, {
        withCredentials: true,
      }),
    );
  }

  /**
   * POST /api/v1/auth/forgot-password (AUTH-08)
   * Always resolves on 204 — the backend never discloses whether the email exists.
   */
  forgotPassword(email: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`${this.BASE}/forgot-password`, { email }),
    );
  }

  /**
   * POST /api/v1/auth/reset-password (AUTH-08)
   * Consumes the admin-delivered single-use token and sets the new password.
   */
  resetPassword(token: string, newPassword: string): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`${this.BASE}/reset-password`, {
        token,
        newPassword,
      }),
    );
  }

  /**
   * POST /api/v1/auth/refresh
   * The browser automatically sends the HttpOnly refresh-token cookie.
   * Returns a fresh access token.
   */
  refresh(): Promise<RefreshResponse> {
    return firstValueFrom(
      this.http.post<RefreshResponse>(
        `${this.BASE}/refresh`,
        {},
        { withCredentials: true },
      ),
    );
  }

  /**
   * POST /api/v1/auth/logout
   * Signals the backend to invalidate the refresh token.
   * The browser will clear the HttpOnly cookie on response.
   */
  logout(): Promise<void> {
    return firstValueFrom(
      this.http.post<void>(`${this.BASE}/logout`, {}, { withCredentials: true }),
    );
  }
}
