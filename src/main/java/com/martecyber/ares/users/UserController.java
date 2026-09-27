package com.martecyber.ares.users;

import com.martecyber.ares.common.PagedResponse;
import com.martecyber.ares.users.dto.CreateUserRequest;
import com.martecyber.ares.users.dto.ResetPasswordRequest;
import com.martecyber.ares.users.dto.UpdateUserRequest;
import com.martecyber.ares.users.dto.UserSummaryDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public PagedResponse<UserSummaryDto> list(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        return PagedResponse.of(service.list(page, size), u -> u);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public UserSummaryDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<UserSummaryDto> create(@Valid @RequestBody CreateUserRequest req) {
        UserSummaryDto created = service.create(req);
        return ResponseEntity.created(URI.create("/api/v1/users/" + created.id())).body(created);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public UserSummaryDto update(@PathVariable Long id, @Valid @RequestBody UpdateUserRequest req) {
        return service.update(id, req);
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/permanent")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> hardDelete(@PathVariable Long id) {
        service.hardDelete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/roles")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public UserSummaryDto assignRole(
        @PathVariable Long id,
        @RequestParam Long roleId,
        @RequestParam Long organizationId
    ) {
        return service.assignRole(id, roleId, organizationId);
    }

    @DeleteMapping("/{id}/roles/{roleId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> removeRole(
        @PathVariable Long id,
        @PathVariable Long roleId,
        @RequestParam Long organizationId
    ) {
        service.removeRole(id, roleId, organizationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Assigns (or clears, with `calendarId: null`) the user's holiday calendar.
     * Dedicated endpoint to keep PATCH semantics clean — `null` here means "clear",
     * distinct from "field missing" which PATCH would treat as "no change".
     */
    @PutMapping("/{id}/holiday-calendar")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public UserSummaryDto setHolidayCalendar(@PathVariable Long id,
                                              @RequestBody HolidayAssignRequest req) {
        return service.setHolidayCalendar(id, req == null ? null : req.calendarId());
    }

    public record HolidayAssignRequest(Long calendarId) {}
}
