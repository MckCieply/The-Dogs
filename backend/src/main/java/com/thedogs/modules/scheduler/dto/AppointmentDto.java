package com.thedogs.modules.scheduler.dto;

import com.thedogs.modules.scheduler.AppointmentStatus;
import java.time.Instant;
import java.util.UUID;

public record AppointmentDto(
    UUID id,
    UUID trainerId,
    UUID clientId,
    Instant startsAt,
    Instant endsAt,
    String location,
    AppointmentStatus status,
    String notes,
    Instant createdAt,
    Instant updatedAt) {}
