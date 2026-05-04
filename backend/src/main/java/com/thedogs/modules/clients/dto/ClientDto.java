package com.thedogs.modules.clients.dto;

import java.time.Instant;
import java.util.UUID;

public record ClientDto(
    UUID id,
    UUID trainerId,
    String name,
    String phone,
    String email,
    String notes,
    Instant createdAt,
    Instant updatedAt) {}
