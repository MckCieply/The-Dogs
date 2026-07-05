import { TestBed } from '@angular/core/testing';
import {
  HttpClientTestingModule,
  HttpTestingController,
} from '@angular/common/http/testing';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { AuthService } from './auth.service';
import { LoginResponse, RefreshResponse } from '../models/auth.models';

describe('AuthService', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [HttpClientTestingModule],
      providers: [AuthService],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('should be created', () => {
    expect(service).toBeTruthy();
  });

  it('login() posts credentials and returns LoginResponse', async () => {
    const mockResponse: LoginResponse = {
      accessToken: 'test-token-abc',
      tokenType: 'Bearer',
      expiresIn: 900,
      email: 'trainer@example.com',
      displayName: 'Trainer',
      roles: ['ROLE_TRAINER'],
    };

    const loginPromise = service.login('trainer@example.com', 'secret');

    const req = httpMock.expectOne('/api/v1/auth/login');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({
      email: 'trainer@example.com',
      password: 'secret',
    });
    expect(req.request.withCredentials).toBe(true);
    req.flush(mockResponse);

    const result = await loginPromise;
    expect(result.accessToken).toBe('test-token-abc');
    expect(result.email).toBe('trainer@example.com');
    expect(result.roles).toContain('ROLE_TRAINER');
  });

  it('refresh() posts to /auth/refresh with credentials', async () => {
    const mockResponse: RefreshResponse = {
      accessToken: 'refreshed-token',
      expiresIn: 900,
      email: 'trainer@example.com',
      displayName: 'Trainer',
      roles: ['ROLE_TRAINER'],
    };

    const refreshPromise = service.refresh();

    const req = httpMock.expectOne('/api/v1/auth/refresh');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBe(true);
    req.flush(mockResponse);

    const result = await refreshPromise;
    expect(result.accessToken).toBe('refreshed-token');
  });

  it('logout() posts to /auth/logout with credentials', async () => {
    const logoutPromise = service.logout();

    const req = httpMock.expectOne('/api/v1/auth/logout');
    expect(req.request.method).toBe('POST');
    expect(req.request.withCredentials).toBe(true);
    req.flush(null);

    await expect(logoutPromise).resolves.toBeNull();
  });

  it('login() rejects on HTTP error', async () => {
    const loginPromise = service.login('bad@example.com', 'wrong');

    const req = httpMock.expectOne('/api/v1/auth/login');
    req.flush({ title: 'Unauthorized', status: 401 }, { status: 401, statusText: 'Unauthorized' });

    await expect(loginPromise).rejects.toBeDefined();
  });
});
