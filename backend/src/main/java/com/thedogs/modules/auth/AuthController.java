package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
import com.thedogs.modules.auth.dto.RegisterRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "Login, refresh, and logout endpoints")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String REFRESH_COOKIE_NAME = "refresh_token";

  private final AuthService authService;
  private final RegistrationService registrationService;
  private final TokenService tokenService;
  private final RefreshTokenService refreshTokenService;

  @Operation(
      summary = "Register a new account",
      description =
          "Creates a user with server-side zxcvbn password-strength enforcement (score >= 3),"
              + " case-insensitive email uniqueness, and first-user-becomes-admin bootstrap."
              + " Issues the same access-token + refresh-cookie pair as /auth/login.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "201",
        description = "Account created; access JWT in body, refresh_token cookie set"),
    @ApiResponse(
        responseCode = "400",
        description = "Validation failed (field_* codes) or password too weak (password_too_weak)"),
    @ApiResponse(responseCode = "409", description = "Email already registered (email_taken)"),
    @ApiResponse(
        responseCode = "429",
        description = "More than 3 registrations from this IP within an hour (too_many_attempts)")
  })
  @PostMapping("/register")
  public ResponseEntity<LoginResponse> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response) {
    AuthService.LoginResult result = registrationService.register(request, httpRequest);
    setRefreshCookie(response, result.refreshToken());
    return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
        .body(result.loginResponse());
  }

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response) {
    AuthService.LoginResult result = authService.login(request, httpRequest);
    setRefreshCookie(response, result.refreshToken());
    return ResponseEntity.ok(result.loginResponse());
  }

  @Operation(
      summary = "Rotate refresh token",
      description =
          "Reads the refresh_token HttpOnly cookie, validates it, issues a new access JWT and a new refresh token (rotation). Returns 401 on invalid, expired, revoked, or reused tokens.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "New access JWT issued; new Set-Cookie: refresh_token set"),
    @ApiResponse(responseCode = "400", description = "No refresh_token cookie present"),
    @ApiResponse(
        responseCode = "401",
        description = "Token invalid, expired, revoked, or reused (theft detected)")
  })
  @PostMapping("/refresh")
  public ResponseEntity<RefreshResponse> refresh(
      HttpServletRequest request, HttpServletResponse response) {
    String refreshToken = extractRefreshCookie(request);
    if (refreshToken == null) {
      return ResponseEntity.badRequest().build();
    }
    AuthService.RefreshResult result = authService.refresh(refreshToken);
    setRefreshCookie(response, result.refreshToken());
    return ResponseEntity.ok(result.refreshResponse());
  }

  @Operation(
      summary = "Logout",
      description =
          "Revokes the refresh token family associated with the cookie and clears the Set-Cookie. Idempotent — safe to call without active auth state; always returns 204.")
  @ApiResponses({
    @ApiResponse(
        responseCode = "204",
        description = "Logged out; Set-Cookie: refresh_token cleared")
  })
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest request) {
    String cookieValue = extractRefreshCookie(request);

    if (cookieValue != null) {
      refreshTokenService.logout(cookieValue);
    }

    // Always clear the cookie regardless of whether a token was found
    ResponseCookie cleared =
        ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .maxAge(0)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .build();

    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared.toString()).build();
  }

  private void setRefreshCookie(HttpServletResponse response, String token) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_COOKIE_NAME, token)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(tokenService.getRefreshTokenTtlSeconds())
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private String extractRefreshCookie(HttpServletRequest request) {
    Cookie[] cookies = request.getCookies();
    if (cookies == null) return null;
    for (Cookie c : cookies) {
      if (REFRESH_COOKIE_NAME.equals(c.getName())) return c.getValue();
    }
    return null;
  }
}
