package com.thedogs.modules.notes.dto;

import java.time.Instant;
import java.util.UUID;

public record NoteDto(
    UUID id,
    UUID dogId,
    UUID trainerId,
    String title,
    String body,
    Instant createdAt,
    Instant updatedAt) {}
