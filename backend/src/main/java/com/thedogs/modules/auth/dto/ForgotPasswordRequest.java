package com.thedogs.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Request body for POST /api/v1/auth/forgot-password (AUTH-05). */
public record ForgotPasswordRequest(@Email @NotBlank String email) {}
