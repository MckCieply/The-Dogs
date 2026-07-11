package com.thedogs.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/v1/auth/confirm-email (AUTH-09). */
public record ConfirmEmailRequest(@NotBlank String token) {}
