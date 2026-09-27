package com.martecyber.ares.users;

import com.martecyber.ares.users.dto.CreatePermissionRequest;
import com.martecyber.ares.users.dto.PermissionDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/permissions")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class PermissionController {

    private final PermissionService svc;

    public PermissionController(PermissionService svc) { this.svc = svc; }

    @GetMapping
    public List<PermissionDto> list() { return svc.list(); }

    @GetMapping("/{id}")
    public PermissionDto get(@PathVariable Long id) { return svc.get(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PermissionDto create(@Valid @RequestBody CreatePermissionRequest req) { return svc.create(req); }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) { svc.delete(id); }
}
