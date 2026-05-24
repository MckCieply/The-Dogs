package com.thedogs.modules.user;

import com.thedogs.modules.user.dto.UserDto;
import jakarta.persistence.EntityNotFoundException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    return userRepository
        .findByEmail(email)
        .orElseThrow(() -> new UsernameNotFoundException("User not found"));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public UserDto getCurrentUser(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));
    return toDto(user);
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public PingResponse getPing(Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found"));
    List<String> roles = user.getRoles().stream().map(Role::getName).toList();
    return new PingResponse(user.getEmail(), roles);
  }

  private UserDto toDto(User user) {
    Set<String> roleNames =
        user.getRoles().stream().map(Role::getName).collect(Collectors.toSet());
    return new UserDto(user.getId(), user.getEmail(), roleNames, user.getCreatedAt());
  }

  public record PingResponse(String email, List<String> roles) {}
}
