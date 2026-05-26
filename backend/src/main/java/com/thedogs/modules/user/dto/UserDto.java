package com.thedogs.modules.user.dto;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record UserDto(UUID id, String email, Set<String> roles, Instant createdAt) {}
