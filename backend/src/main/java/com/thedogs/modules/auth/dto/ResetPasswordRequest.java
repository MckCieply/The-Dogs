package com.thedogs.modules.auth.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST /api/v1/auth/reset-password (AUTH-05). The spec's snake_case {@code
 * new_password} is accepted via {@link JsonAlias} alongside the project-wide camelCase convention.
 * Length bounds mirror AUTH-04 and run before the zxcvbn strength gate.
 */
public record ResetPasswordRequest(
    @NotBlank String token,
    @JsonAlias("new_password") @NotBlank @Size(min = 10, max = 128) String newPassword) {}
