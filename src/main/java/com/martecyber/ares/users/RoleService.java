package com.martecyber.ares.users;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.users.dto.CreateRoleRequest;
import com.martecyber.ares.users.dto.RoleDto;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;

    public RoleService(RoleRepository roles, PermissionRepository permissions) {
        this.roles = roles;
        this.permissions = permissions;
    }

    @Transactional
    public List<RoleDto> list() {
        return roles.findAll().stream().map(RoleDto::from).toList();
    }

    @Transactional
    public RoleDto get(Long id) {
        return RoleDto.from(roles.findById(id).orElseThrow(() -> NotFoundException.of("role", id)));
    }

    @Transactional
    public RoleDto create(CreateRoleRequest req) {
        if (roles.findByCode(req.code()).isPresent()) {
            throw new ConflictException("Role '" + req.code() + "' already exists");
        }
        Role role = new Role();
        role.setCode(req.code().toUpperCase());
        role.setName(req.name().trim());
        role.setDescription(req.description() != null ? req.description().trim() : null);
        role.setSystem(false);
        return RoleDto.from(roles.save(role));
    }

    @Transactional
    public void delete(Long id) {
        Role role = roles.findById(id).orElseThrow(() -> NotFoundException.of("role", id));
        if (role.isSystem()) throw new ConflictException("System roles cannot be deleted");
        roles.deleteById(id);
    }

    @Transactional
    public RoleDto addPermission(Long roleId, Long permissionId) {
        Role role = roles.findById(roleId).orElseThrow(() -> NotFoundException.of("role", roleId));
        Permission perm = permissions.findById(permissionId)
            .orElseThrow(() -> NotFoundException.of("permission", permissionId));
        role.getPermissions().add(perm);
        return RoleDto.from(roles.save(role));
    }

    @Transactional
    public RoleDto removePermission(Long roleId, Long permissionId) {
        Role role = roles.findById(roleId).orElseThrow(() -> NotFoundException.of("role", roleId));
        role.getPermissions().removeIf(p -> p.getId().equals(permissionId));
        return RoleDto.from(roles.save(role));
    }
}
