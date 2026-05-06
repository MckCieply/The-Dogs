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
  roles: string[];
}

export interface RefreshResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
}

export interface AuthUser {
  email: string;
  roles: string[];
}

export interface ApiError {
  type: string;
  title: string;
  status: number;
  detail: string;
  instance?: string;
}
