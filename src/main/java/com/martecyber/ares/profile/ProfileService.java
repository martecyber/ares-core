package com.martecyber.ares.profile;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.holidays.HolidayCalendar;
import com.martecyber.ares.holidays.HolidayCalendarRepository;
import com.martecyber.ares.holidays.HolidayCalendarService;
import com.martecyber.ares.holidays.HolidayDtos.CalendarDayDto;
import com.martecyber.ares.users.User;
import com.martecyber.ares.users.UserRepository;
import com.martecyber.ares.users.UserRoleRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class ProfileService {

    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024; // 2 MB

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final PasswordEncoder passwordEncoder;
    private final UserOutOfOfficeRepository oooRepo;
    private final HolidayCalendarRepository holidayCalendars;
    private final HolidayCalendarService holidayService;

    public ProfileService(UserRepository users, UserRoleRepository userRoles,
                          PasswordEncoder passwordEncoder, UserOutOfOfficeRepository oooRepo,
                          HolidayCalendarRepository holidayCalendars,
                          HolidayCalendarService holidayService) {
        this.users = users;
        this.userRoles = userRoles;
        this.passwordEncoder = passwordEncoder;
        this.oooRepo = oooRepo;
        this.holidayCalendars = holidayCalendars;
        this.holidayService = holidayService;
    }

    public ProfileDto get(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        return toDto(u);
    }

    @Transactional
    public ProfileDto update(Long userId, UpdateProfileRequest req) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        if (req.displayName() != null && !req.displayName().isBlank()) {
            u.setDisplayName(req.displayName().trim());
        }
        u.setUpdatedAt(OffsetDateTime.now());
        return toDto(users.save(u));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        String storedHash = new String(u.getPasswordHash(), StandardCharsets.UTF_8);
        if (!passwordEncoder.matches(req.currentPassword(), storedHash)) {
            throw new BadCredentialsException("Current password is incorrect");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()).getBytes(StandardCharsets.UTF_8));
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
    }

    @Transactional
    public void uploadAvatar(Long userId, MultipartFile file) throws IOException {
        if (file.isEmpty()) throw new IllegalArgumentException("File is empty");
        if (file.getSize() > MAX_AVATAR_BYTES) throw new IllegalArgumentException("Avatar must be under 2 MB");
        String mime = file.getContentType();
        if (mime == null || !mime.startsWith("image/")) throw new IllegalArgumentException("File must be an image");

        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        u.setAvatarData(file.getBytes());
        u.setAvatarMime(mime);
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
    }

    @Transactional
    public void deleteAvatar(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        u.setAvatarData(null);
        u.setAvatarMime(null);
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
    }

    public record AvatarData(byte[] data, String mime) {}

    public AvatarData getAvatar(Long userId) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        if (u.getAvatarData() == null) throw new NotFoundException("Avatar not found for user " + userId);
        return new AvatarData(u.getAvatarData(), u.getAvatarMime() != null ? u.getAvatarMime() : "image/jpeg");
    }

    // ── Out of Office ─────────────────────────────────────────────

    public List<UserOutOfOfficeDto> listOoo(Long userId) {
        return oooRepo.findByUserIdOrderByStartDateAsc(userId).stream()
            .map(UserOutOfOfficeDto::from).toList();
    }

    public List<UserOutOfOfficeDto> listOooByRange(Long userId, LocalDate start, LocalDate end) {
        return oooRepo.findByUserIdAndRange(userId, start, end).stream()
            .map(UserOutOfOfficeDto::from).toList();
    }

    public List<UserOutOfOfficeDto> listAllOooByRange(LocalDate start, LocalDate end) {
        return oooRepo.findByRange(start, end).stream()
            .map(UserOutOfOfficeDto::from).toList();
    }

    @Transactional
    public UserOutOfOfficeDto createOoo(Long userId, CreateOooRequest req) {
        LocalDate start = LocalDate.parse(req.startDate());
        LocalDate end   = LocalDate.parse(req.endDate());
        if (end.isBefore(start)) throw new IllegalArgumentException("end_date must be on or after start_date");
        UserOutOfOffice ooo = new UserOutOfOffice(userId, start, end, req.reason());
        return UserOutOfOfficeDto.from(oooRepo.save(ooo));
    }

    @Transactional
    public void deleteOoo(Long userId, Long oooId) {
        UserOutOfOffice ooo = oooRepo.findById(oooId)
            .orElseThrow(() -> NotFoundException.of("out-of-office", oooId));
        if (!ooo.getUserId().equals(userId))
            throw new NotFoundException("out-of-office entry " + oooId + " not found");
        oooRepo.delete(ooo);
    }

    private ProfileDto toDto(User u) {
        List<String> roles = userRoles.findByUserId(u.getId()).stream()
            .map(ur -> ur.getRole().getCode())
            .toList();
        Long calId = u.getHolidayCalendarId();
        String calName = calId == null ? null
            : holidayCalendars.findById(calId).map(HolidayCalendar::getName).orElse(null);
        return new ProfileDto(u.getId(), u.getEmail(), u.getDisplayName(),
            u.getAvatarData() != null, roles, calId, calName);
    }

    // ── Holiday calendar (read-only — assignment is admin-only via UserService) ──

    public List<CalendarDayDto> listMyHolidays(Long userId, LocalDate start, LocalDate end) {
        return holidayService.forUserInRange(userId, start, end);
    }
}
