package com.martecyber.ares.profile;

import com.martecyber.ares.holidays.HolidayDtos.CalendarDayDto;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final ProfileService service;

    public ProfileController(ProfileService service) { this.service = service; }

    @GetMapping("/me")
    public ProfileDto me(Authentication auth) {
        return service.get(userId(auth));
    }

    @PatchMapping("/me")
    public ProfileDto update(Authentication auth, @Valid @RequestBody UpdateProfileRequest req) {
        return service.update(userId(auth), req);
    }

    @PostMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(Authentication auth, @Valid @RequestBody ChangePasswordRequest req) {
        service.changePassword(userId(auth), req);
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void uploadAvatar(Authentication auth, @RequestParam("file") MultipartFile file) throws IOException {
        service.uploadAvatar(userId(auth), file);
    }

    @DeleteMapping("/me/avatar")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAvatar(Authentication auth) {
        service.deleteAvatar(userId(auth));
    }

    // ── Out of Office ─────────────────────────────────────────────

    @GetMapping("/me/ooo")
    public List<UserOutOfOfficeDto> listMyOoo(Authentication auth,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        Long uid = userId(auth);
        if (start != null && end != null)
            return service.listOooByRange(uid, LocalDate.parse(start), LocalDate.parse(end));
        return service.listOoo(uid);
    }

    @PostMapping("/me/ooo")
    @ResponseStatus(HttpStatus.CREATED)
    public UserOutOfOfficeDto createOoo(Authentication auth, @Valid @RequestBody CreateOooRequest req) {
        return service.createOoo(userId(auth), req);
    }

    @DeleteMapping("/me/ooo/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteOoo(Authentication auth, @PathVariable Long id) {
        service.deleteOoo(userId(auth), id);
    }

    @GetMapping("/{userId}/ooo")
    public List<UserOutOfOfficeDto> userOoo(@PathVariable Long userId,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        if (start != null && end != null)
            return service.listOooByRange(userId, LocalDate.parse(start), LocalDate.parse(end));
        return service.listOoo(userId);
    }

    // ── Holiday calendar (read-only for the user; assignment is admin-only) ──

    /** Resolved holiday days for the current user inside [start, end]. */
    @GetMapping("/me/holidays")
    public List<CalendarDayDto> myHolidays(Authentication auth,
            @RequestParam String start, @RequestParam String end) {
        return service.listMyHolidays(userId(auth), LocalDate.parse(start), LocalDate.parse(end));
    }

    // Public endpoint — no auth required, serves avatar image bytes
    @GetMapping("/{userId}/avatar")
    public ResponseEntity<byte[]> avatar(@PathVariable Long userId) {
        ProfileService.AvatarData av = service.getAvatar(userId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(av.mime()))
            .header("Cache-Control", "public, max-age=86400")
            .body(av.data());
    }

    private static Long userId(Authentication auth) {
        return Long.parseLong(auth.getName());
    }
}
