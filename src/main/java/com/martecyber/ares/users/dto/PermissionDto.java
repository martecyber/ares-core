package com.martecyber.ares.users.dto;

import com.martecyber.ares.users.Permission;

public record PermissionDto(Long id, String code, String description) {
    public static PermissionDto from(Permission p) {
        return new PermissionDto(p.getId(), p.getCode(), p.getDescription());
    }
}
