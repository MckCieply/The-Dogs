package com.thedogs.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auth/register (AUTH-04).
 *
 * <p>Bean validation covers the structural rules (AC-4..AC-6): they run before the zxcvbn strength
 * check in the service layer, so a too-short password is reported as {@code field_too_short}, not
 * {@code password_too_weak}. The spec's snake_case {@code display_name} is accepted via {@link
 * JsonAlias} alongside the project-wide camelCase convention.
 */
public record RegisterRequest(
    @Email @NotBlank String email,
    @NotBlank @Size(min = 10, max = 128) String password,
    @JsonAlias("display_name") @NotBlank @Size(min = 1, max = 80) String displayName) {}
