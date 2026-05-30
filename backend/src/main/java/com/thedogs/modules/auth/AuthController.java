package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
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

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String REFRESH_COOKIE_NAME = "refresh_token";

  private final AuthService authService;
  private final TokenService tokenService;
  private final RefreshTokenService refreshTokenService;

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response) {
    AuthService.LoginResult result = authService.login(request, httpRequest);
    setRefreshCookie(response, result.refreshToken());
    return ResponseEntity.ok(result.loginResponse());
  }

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

  /**
   * Revokes the refresh token family associated with the cookie and clears the cookie. Always
   * returns 204 — idempotent, safe to call without active auth state (AC-4).
   */
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
    Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, token);
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setPath("/api/v1/auth");
    cookie.setMaxAge((int) tokenService.getRefreshTokenTtlSeconds());
    cookie.setAttribute("SameSite", "Strict");
    response.addCookie(cookie);
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
