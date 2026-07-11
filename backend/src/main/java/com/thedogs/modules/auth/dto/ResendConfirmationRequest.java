package com.thedogs.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/v1/auth/resend-confirmation (AUTH-09). */
public record ResendConfirmationRequest(@Email @NotBlank String email) {}
