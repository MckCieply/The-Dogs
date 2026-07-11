package com.thedogs.modules.auth.dto;

import java.time.Instant;

/**
 * Response body for GET /api/v1/admin/email-verification-tokens (AUTH-09). Carries the freshly
 * rotated token value — the only non-dev-log path where a verification token leaves the backend.
 */
public record AdminVerificationTokenResponse(String token, Instant expiresAt) {}
