package com.thedogs.modules.dogs.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record DogDto(
    UUID id,
    UUID clientId,
    String name,
    String breed,
    LocalDate birthdate,
    String sex,
    String metadata,
    boolean archived,
    Instant createdAt,
    Instant updatedAt) {}
