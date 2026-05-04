package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  private static final String REFRESH_COOKIE_NAME = "refreshToken";

  private final AuthService authService;
  private final TokenService tokenService;

  @PostMapping("/login")
  public ResponseEntity<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    AuthService.LoginResult result = authService.login(request);
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

  private void setRefreshCookie(HttpServletResponse response, String token) {
    Cookie cookie = new Cookie(REFRESH_COOKIE_NAME, token);
    cookie.setHttpOnly(true);
    cookie.setSecure(true);
    cookie.setPath("/api/v1/auth/refresh");
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
