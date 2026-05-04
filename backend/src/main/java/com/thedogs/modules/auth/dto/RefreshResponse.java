package com.thedogs.modules.auth.dto;

public record RefreshResponse(String accessToken, long expiresIn) {}
