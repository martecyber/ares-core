package com.martecyber.ares.users.dto;

import com.martecyber.ares.users.Role;
import java.util.List;

public record RoleDto(Long id, String code, String name, String description, boolean system,
                      List<String> permissionCodes) {
    public static RoleDto from(Role r) {
        List<String> perms = r.getPermissions().stream()
            .map(p -> p.getCode()).sorted().toList();
        return new RoleDto(r.getId(), r.getCode(), r.getName(), r.getDescription(), r.isSystem(), perms);
    }
}
