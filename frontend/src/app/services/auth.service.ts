import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import {
  LoginRequest,
  LoginResponse,
  RefreshResponse,
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
