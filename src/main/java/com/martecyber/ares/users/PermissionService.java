package com.martecyber.ares.users;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.users.dto.CreatePermissionRequest;
import com.martecyber.ares.users.dto.PermissionDto;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PermissionService {

    private final PermissionRepository repo;

    public PermissionService(PermissionRepository repo) { this.repo = repo; }

    public List<PermissionDto> list() {
        return repo.findAll().stream().map(PermissionDto::from).toList();
    }

    public PermissionDto get(Long id) {
        return PermissionDto.from(repo.findById(id).orElseThrow(() -> NotFoundException.of("permission", id)));
    }

    public PermissionDto create(CreatePermissionRequest req) {
        if (repo.findByCode(req.code()).isPresent()) {
            throw new ConflictException("Permission '" + req.code() + "' already exists");
        }
        Permission p = new Permission();
        p.setCode(req.code().toUpperCase());
        p.setDescription(req.description());
        return PermissionDto.from(repo.save(p));
    }

    public void delete(Long id) {
        repo.findById(id).orElseThrow(() -> NotFoundException.of("permission", id));
        repo.deleteById(id);
    }
}
