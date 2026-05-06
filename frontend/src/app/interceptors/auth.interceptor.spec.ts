import { TestBed } from '@angular/core/testing';
import {
  HttpClient,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { authInterceptor } from './auth.interceptor';
import { AuthStore } from '../services/auth.store';

describe('authInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  let authStore: InstanceType<typeof AuthStore>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
    authStore = TestBed.inject(AuthStore);
  });

  afterEach(() => {
    httpMock.verify();
  });

  it('adds Authorization header when token is set', async () => {
    authStore.setToken('my-token', { email: 'trainer@example.com', roles: ['ROLE_TRAINER'] });

    const req$ = http.get('/api/v1/dogs').subscribe();
    const req = httpMock.expectOne('/api/v1/dogs');
    expect(req.request.headers.get('Authorization')).toBe('Bearer my-token');
    req.flush([]);
    req$.unsubscribe();
  });

  it('does not add Authorization header when no token', async () => {
    authStore.clearToken();

    http.get('/api/v1/dogs').subscribe();
    const req = httpMock.expectOne('/api/v1/dogs');
    expect(req.request.headers.get('Authorization')).toBeNull();
    req.flush([]);
  });

  it('on 401, attempts silent refresh and retries original request', async () => {
    authStore.setToken('expired-token', { email: 'trainer@example.com', roles: ['ROLE_TRAINER'] });

    // Spy on authStore.refresh to return new token
    vi.spyOn(authStore, 'refresh').mockResolvedValue('new-token');

    const resultPromise = new Promise<unknown>((resolve, reject) => {
      http.get('/api/v1/dogs').subscribe({ next: resolve, error: reject });
    });

    // First request gets 401
    const firstReq = httpMock.expectOne('/api/v1/dogs');
    expect(firstReq.request.headers.get('Authorization')).toBe('Bearer expired-token');
    firstReq.flush({}, { status: 401, statusText: 'Unauthorized' });

    // After refresh, retry should go out with new token
    await new Promise((r) => setTimeout(r, 10)); // let microtasks flush
    const retryReq = httpMock.expectOne('/api/v1/dogs');
    expect(retryReq.request.headers.get('Authorization')).toBe('Bearer new-token');
    retryReq.flush([{ id: 1, name: 'Rex' }]);

    const result = await resultPromise;
    expect(result).toBeDefined();
  });

  it('clears token when refresh fails on 401', async () => {
    authStore.setToken('expired-token', { email: 'trainer@example.com', roles: ['ROLE_TRAINER'] });

    vi.spyOn(authStore, 'refresh').mockRejectedValue(new Error('Refresh failed'));

    const resultPromise = new Promise<unknown>((resolve, reject) => {
      http.get('/api/v1/dogs').subscribe({ next: resolve, error: reject });
    });

    const firstReq = httpMock.expectOne('/api/v1/dogs');
    firstReq.flush({}, { status: 401, statusText: 'Unauthorized' });

    await expect(resultPromise).rejects.toBeDefined();
    expect(authStore.isAuthenticated()).toBe(false);
  });
});
