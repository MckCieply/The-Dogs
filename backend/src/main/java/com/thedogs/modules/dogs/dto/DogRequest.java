package com.thedogs.modules.dogs.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.UUID;

public record DogRequest(
    @NotNull UUID clientId,
    @NotBlank String name,
    String breed,
    LocalDate birthdate,
    String sex,
    String metadata) {}
