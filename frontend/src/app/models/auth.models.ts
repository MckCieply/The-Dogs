/**
 * Auth domain models — hand-written until openapi-typescript generation
 * is wired up. These mirror the Spring Boot DTOs defined in ADR-0003.
 */

export interface LoginRequest {
  email: string;
  password: string;
}

export interface LoginResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number; // seconds
  email: string;
  displayName: string;
  roles: string[];
}

export interface RefreshResponse {
  accessToken: string;
  expiresIn: number;
  // Identity fields so a hard reload can restore the whole session from the
  // refresh cookie alone (access token + user are memory-only per ADR-0003).
  email: string;
  displayName: string;
  roles: string[];
}

export interface AuthUser {
  email: string;
  displayName: string;
  roles: string[];
}

export interface RegisterRequest {
  email: string;
  password: string;
  displayName: string;
}

export interface ForgotPasswordRequest {
  email: string;
}

export interface ResetPasswordRequest {
  token: string;
  newPassword: string;
}

/** One entry of the RFC 7807 `errors` array emitted by the backend. */
export interface ApiFieldError {
  code: string;
  field?: string;
  /** AUTH-04: zxcvbn score (0–4) attached to password_too_weak errors. */
  password_score?: number;
}

export interface ApiError {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
}
