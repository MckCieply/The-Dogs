package com.thedogs.modules.user.dto;

import com.thedogs.modules.user.Role;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserDto(UUID id, String email, List<Role> roles, Instant createdAt) {}
