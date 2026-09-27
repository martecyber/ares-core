package com.martecyber.ares.kb.schedule;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/kb/schedules")
@PreAuthorize("hasRole('MSSP_ADMIN')")
public class KbSyncScheduleController {

    private final KbSyncScheduleService service;

    public KbSyncScheduleController(KbSyncScheduleService service) {
        this.service = service;
    }

    @GetMapping
    public List<KbSyncScheduleDto> list(@RequestParam String type) {
        return service.list(type);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KbSyncScheduleDto create(@RequestBody CreateRequest req) {
        return service.create(req.syncType(), req.cronExpression());
    }

    @PatchMapping("/{id}/enabled")
    public KbSyncScheduleDto setEnabled(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        return service.setEnabled(id, Boolean.TRUE.equals(body.get("enabled")));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    public record CreateRequest(@NotBlank String syncType, @NotBlank String cronExpression) {}
}
