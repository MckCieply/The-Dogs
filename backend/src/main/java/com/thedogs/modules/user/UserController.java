package com.thedogs.modules.user;

import com.thedogs.modules.user.dto.UserDto;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  @GetMapping
  public ResponseEntity<UserDto> me(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(userService.getCurrentUser(userId));
  }

  @GetMapping("/ping")
  public ResponseEntity<UserService.PingResponse> ping(@AuthenticationPrincipal Jwt jwt) {
    return ResponseEntity.ok(userService.getPing(jwt));
  }
}
