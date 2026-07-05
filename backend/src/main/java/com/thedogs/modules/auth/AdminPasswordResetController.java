package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.AdminResetTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin retrieval of password-reset tokens (AUTH-05, ADR-0013). The ONLY non-dev-log path where a
 * reset token value leaves the backend. Locked to ROLE_ADMIN here (braces) and again at the service
 * layer (belt).
 */
@Tag(name = "Admin — password reset", description = "Out-of-band reset-token retrieval (no SMTP)")
@RestController
@RequestMapping("/api/v1/admin/password-reset-tokens")
@RequiredArgsConstructor
@Validated
public class AdminPasswordResetController {

  private final PasswordResetService passwordResetService;

  @Operation(
      summary = "Rotate and fetch the reset token for an email",
      description =
          "Returns a FRESH token for the account (the outstanding token is invalidated on every"
              + " call — tokens are hashed at rest, so the previous value cannot be replayed)."
              + " 404 when the email is unknown or has no active token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Fresh token value + expiry"),
    @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_ADMIN (forbidden)"),
    @ApiResponse(responseCode = "404", description = "Unknown email or no active token (not_found)")
  })
  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping
  public ResponseEntity<AdminResetTokenResponse> getToken(
      @RequestParam("email") @Email @NotBlank String email) {
    return ResponseEntity.ok(passwordResetService.rotateAndGetTokenForEmail(email));
  }
}
