package com.martecyber.ares.users;

import java.io.Serializable;
import java.util.Objects;

public class UserRoleId implements Serializable {

    private Long userId;
    private Long roleId;
    private Long organizationId;

    public UserRoleId() {}

    public UserRoleId(Long userId, Long roleId, Long organizationId) {
        this.userId = userId;
        this.roleId = roleId;
        this.organizationId = organizationId;
    }

    public Long getUserId() { return userId; }
    public Long getRoleId() { return roleId; }
    public Long getOrganizationId() { return organizationId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserRoleId that)) return false;
        return Objects.equals(userId, that.userId)
            && Objects.equals(roleId, that.roleId)
            && Objects.equals(organizationId, that.organizationId);
    }

    @Override
    public int hashCode() { return Objects.hash(userId, roleId, organizationId); }
}
