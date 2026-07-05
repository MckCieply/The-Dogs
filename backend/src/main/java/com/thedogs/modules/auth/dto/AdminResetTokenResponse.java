package com.thedogs.modules.auth.dto;

import java.time.Instant;

/**
 * Response body for GET /api/v1/admin/password-reset-tokens (AUTH-05). Carries the freshly rotated
 * token value — the only non-dev-log path where a reset token leaves the backend.
 */
public record AdminResetTokenResponse(String token, Instant expiresAt) {}
