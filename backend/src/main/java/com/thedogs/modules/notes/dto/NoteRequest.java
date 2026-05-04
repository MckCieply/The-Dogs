package com.thedogs.modules.notes.dto;

import jakarta.validation.constraints.NotBlank;

public record NoteRequest(@NotBlank String title, @NotBlank String body) {}
