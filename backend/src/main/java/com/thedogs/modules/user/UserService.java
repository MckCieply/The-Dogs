package com.thedogs.modules.user;

import com.thedogs.modules.user.dto.UserDto;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));
  }

  @PreAuthorize("hasAnyRole('TRAINER','ADMIN')")
  public UserDto getCurrentUser(UUID userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    return toDto(user);
  }

  private UserDto toDto(User user) {
    return new UserDto(user.getId(), user.getEmail(), user.getRoles(), user.getCreatedAt());
  }
}
