package com.martecyber.ares.users;

import com.martecyber.ares.users.dto.CreateRoleRequest;
import com.martecyber.ares.users.dto.RoleDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class RoleController {

    private final RoleService svc;

    public RoleController(RoleService svc) { this.svc = svc; }

    @GetMapping
    public List<RoleDto> list() { return svc.list(); }

    @GetMapping("/{id}")
    public RoleDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping
    public ResponseEntity<RoleDto> create(@Valid @RequestBody CreateRoleRequest req) {
        RoleDto created = svc.create(req);
        return ResponseEntity.created(URI.create("/api/v1/roles/" + created.id())).body(created);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }

    @PostMapping("/{id}/permissions/{permissionId}")
    public RoleDto addPermission(@PathVariable Long id, @PathVariable Long permissionId) {
        return svc.addPermission(id, permissionId);
    }

    @DeleteMapping("/{id}/permissions/{permissionId}")
    public RoleDto removePermission(@PathVariable Long id, @PathVariable Long permissionId) {
        return svc.removePermission(id, permissionId);
    }
}
