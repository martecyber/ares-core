package com.martecyber.ares.holidays;

import com.martecyber.ares.holidays.HolidayDtos.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin CRUD for holiday calendars and their day entries. Users opt into a calendar
 * via the profile endpoints (see ProfileController).
 */
@RestController
@RequestMapping("/api/v1/holiday-calendars")
public class HolidayCalendarController {

    private final HolidayCalendarService service;

    public HolidayCalendarController(HolidayCalendarService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<CalendarDto> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public CalendarDto get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<CalendarDto> create(@RequestBody CreateCalendarRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public CalendarDto update(@PathVariable Long id, @RequestBody UpdateCalendarRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ── Days ──────────────────────────────────────────────────────

    @GetMapping("/{id}/days")
    @PreAuthorize("hasAnyRole('MSSP_ADMIN','MSSP_OPERATOR')")
    public List<CalendarDayDto> listDays(@PathVariable Long id) {
        return service.listDays(id);
    }

    @PostMapping("/{id}/days")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<CalendarDayDto> addDay(@PathVariable Long id,
                                                  @RequestBody CreateDayRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addDay(id, req));
    }

    @DeleteMapping("/{id}/days/{dayId}")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public ResponseEntity<Void> deleteDay(@PathVariable Long id, @PathVariable Long dayId) {
        service.deleteDay(id, dayId);
        return ResponseEntity.noContent().build();
    }
}
