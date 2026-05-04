package com.thedogs.modules.scheduler.dto;

import com.thedogs.modules.scheduler.AppointmentStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public record AppointmentRequest(
    @NotNull UUID clientId,
    @NotNull Instant startsAt,
    @NotNull Instant endsAt,
    String location,
    AppointmentStatus status,
    String notes) {}
