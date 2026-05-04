package com.thedogs.modules.auth.dto;

public record LoginResponse(String accessToken, long expiresIn) {}
