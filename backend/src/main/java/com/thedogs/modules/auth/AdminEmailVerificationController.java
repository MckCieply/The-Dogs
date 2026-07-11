package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.AdminVerificationTokenResponse;
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
 * Admin retrieval of email-verification tokens (AUTH-09, ADR-0013 pattern). The ONLY non-dev-log
 * path where a verification token value leaves the backend. Locked to ROLE_ADMIN here (braces) and
 * again at the service layer (belt).
 */
@Tag(
    name = "Admin — email verification",
    description = "Out-of-band verification-token retrieval (no SMTP)")
@RestController
@RequestMapping("/api/v1/admin/email-verification-tokens")
@RequiredArgsConstructor
@Validated
public class AdminEmailVerificationController {

  private final EmailVerificationService emailVerificationService;

  @Operation(
      summary = "Rotate and fetch the verification token for an email",
      description =
          "Returns a FRESH token for the account (the outstanding token is invalidated on every"
              + " call — tokens are hashed at rest, so the previous value cannot be replayed)."
              + " 404 when the email is unknown, already verified, or has no active token.")
  @ApiResponses({
    @ApiResponse(responseCode = "200", description = "Fresh token value + expiry"),
    @ApiResponse(responseCode = "403", description = "Caller lacks ROLE_ADMIN (forbidden)"),
    @ApiResponse(
        responseCode = "404",
        description = "Unknown email, already verified, or no active token (not_found)")
  })
  @PreAuthorize("hasRole('ADMIN')")
  @GetMapping
  public ResponseEntity<AdminVerificationTokenResponse> getToken(
      @RequestParam("email") @Email @NotBlank String email) {
    return ResponseEntity.ok(emailVerificationService.rotateAndGetTokenForEmail(email));
  }
}
