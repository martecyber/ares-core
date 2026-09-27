package com.martecyber.ares.auth.dto;

import java.util.List;

public record UserDto(
    Long id,
    String email,
    String displayName,
    List<String> roles,
    List<String> permissions
) {}
