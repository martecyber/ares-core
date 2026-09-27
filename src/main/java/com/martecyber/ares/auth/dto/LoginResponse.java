package com.martecyber.ares.auth.dto;

public record LoginResponse(
    String accessToken,
    String refreshToken,
    UserDto user
) {}
