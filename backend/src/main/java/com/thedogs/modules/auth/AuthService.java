package com.thedogs.modules.auth;

import com.thedogs.modules.auth.dto.LoginRequest;
import com.thedogs.modules.auth.dto.LoginResponse;
import com.thedogs.modules.auth.dto.RefreshResponse;
import com.thedogs.modules.user.User;
import com.thedogs.modules.user.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

  private final AuthenticationManager authenticationManager;
  private final TokenService tokenService;
  private final RefreshTokenStore refreshTokenStore;
  private final UserRepository userRepository;

  public record LoginResult(LoginResponse loginResponse, String refreshToken) {}

  @Transactional
  public LoginResult login(LoginRequest request) {
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(request.email(), request.password()));

    User user =
        userRepository
            .findByEmail(request.email())
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    String accessToken = tokenService.generateAccessToken(user);
    String refreshToken = tokenService.generateRefreshToken();
    refreshTokenStore.store(refreshToken, user.getId());

    return new LoginResult(
        new LoginResponse(accessToken, tokenService.getAccessTokenTtlSeconds()), refreshToken);
  }

  @Transactional
  public RefreshResult refresh(String oldRefreshToken) {
    UUID userId =
        refreshTokenStore
            .getUserId(oldRefreshToken)
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired refresh token"));

    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));

    // Rotate the refresh token
    refreshTokenStore.revoke(oldRefreshToken);
    String newRefreshToken = tokenService.generateRefreshToken();
    refreshTokenStore.store(newRefreshToken, userId);

    String newAccessToken = tokenService.generateAccessToken(user);
    return new RefreshResult(
        new RefreshResponse(newAccessToken, tokenService.getAccessTokenTtlSeconds()),
        newRefreshToken);
  }

  public record RefreshResult(RefreshResponse refreshResponse, String refreshToken) {}
}
