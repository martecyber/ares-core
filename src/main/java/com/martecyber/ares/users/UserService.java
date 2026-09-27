package com.martecyber.ares.users;

import com.martecyber.ares.common.ConflictException;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.holidays.HolidayCalendar;
import com.martecyber.ares.holidays.HolidayCalendarRepository;
import com.martecyber.ares.users.dto.CreateUserRequest;
import com.martecyber.ares.users.dto.ResetPasswordRequest;
import com.martecyber.ares.users.dto.UpdateUserRequest;
import com.martecyber.ares.users.dto.UserSummaryDto;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;

@Service
public class UserService {

    private final UserRepository users;
    private final UserRoleRepository userRoles;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final HolidayCalendarRepository holidayCalendars;

    public UserService(UserRepository users, UserRoleRepository userRoles,
                       RoleRepository roles, PasswordEncoder passwordEncoder,
                       HolidayCalendarRepository holidayCalendars) {
        this.users = users;
        this.userRoles = userRoles;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.holidayCalendars = holidayCalendars;
    }

    public Page<UserSummaryDto> list(int page, int size) {
        Pageable p = PageRequest.of(
            Math.max(page, 0),
            Math.min(Math.max(size, 1), 200),
            Sort.by(Sort.Direction.DESC, "createdAt"));
        return users.findAll(p).map(u -> toDto(u, roleCodes(u.getId())));
    }

    public UserSummaryDto get(Long id) {
        User u = users.findById(id).orElseThrow(() -> NotFoundException.of("user", id));
        return toDto(u, roleCodes(id));
    }

    @Transactional
    public UserSummaryDto create(CreateUserRequest req) {
        if (users.findByEmailIgnoreCase(req.email()).isPresent()) {
            throw new ConflictException("User with email '" + req.email() + "' already exists");
        }
        User u = new User();
        u.setEmail(req.email().trim());
        u.setDisplayName(req.displayName().trim());
        u.setPasswordHash(passwordEncoder.encode(req.password()).getBytes(StandardCharsets.UTF_8));
        u.setStatus("active");
        u.setMfaEnforced(false);
        OffsetDateTime now = OffsetDateTime.now();
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return toDto(users.save(u), List.of());
    }

    @Transactional
    public UserSummaryDto update(Long id, UpdateUserRequest req) {
        User u = users.findById(id).orElseThrow(() -> NotFoundException.of("user", id));
        if (req.displayName() != null && !req.displayName().isBlank()) u.setDisplayName(req.displayName().trim());
        if (req.status() != null && !req.status().isBlank()) u.setStatus(req.status());
        if (req.mfaEnforced() != null) u.setMfaEnforced(req.mfaEnforced());
        u.setUpdatedAt(OffsetDateTime.now());
        return toDto(users.save(u), roleCodes(id));
    }

    @Transactional
    public void resetPassword(Long id, ResetPasswordRequest req) {
        User u = users.findById(id).orElseThrow(() -> NotFoundException.of("user", id));
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()).getBytes(StandardCharsets.UTF_8));
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
    }

    @Transactional
    public void delete(Long id) {
        User u = users.findById(id).orElseThrow(() -> NotFoundException.of("user", id));
        u.setStatus("deleted");
        u.setUpdatedAt(OffsetDateTime.now());
        users.save(u);
    }

    @Transactional
    public void hardDelete(Long id) {
        User u = users.findById(id).orElseThrow(() -> NotFoundException.of("user", id));
        users.delete(u);
    }

    @Transactional
    public UserSummaryDto assignRole(Long userId, Long roleId, Long organizationId) {
        users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        roles.findById(roleId).orElseThrow(() -> NotFoundException.of("role", roleId));

        UserRoleId pk = new UserRoleId(userId, roleId, organizationId);
        if (!userRoles.existsById(pk)) {
            UserRole ur = new UserRole();
            ur.setUserId(userId);
            ur.setRoleId(roleId);
            ur.setOrganizationId(organizationId);
            userRoles.save(ur);
        }
        return toDto(users.findById(userId).orElseThrow(), roleCodes(userId));
    }

    @Transactional
    public void removeRole(Long userId, Long roleId, Long organizationId) {
        userRoles.deleteByUserIdAndRoleIdAndOrganizationId(userId, roleId, organizationId);
    }

    private List<String> roleCodes(Long userId) {
        return userRoles.findByUserId(userId).stream()
            .map(UserRole::getRole)
            .filter(r -> r != null)
            .map(Role::getCode)
            .distinct()
            .toList();
    }

    @Transactional
    public UserSummaryDto setHolidayCalendar(Long userId, Long calendarId) {
        User u = users.findById(userId).orElseThrow(() -> NotFoundException.of("user", userId));
        if (calendarId != null && !holidayCalendars.existsById(calendarId)) {
            throw NotFoundException.of("holiday calendar", calendarId);
        }
        u.setHolidayCalendarId(calendarId);
        u.setUpdatedAt(OffsetDateTime.now());
        return toDto(users.save(u), roleCodes(userId));
    }

    UserSummaryDto toDto(User u, List<String> roleCodes) {
        Long calId = u.getHolidayCalendarId();
        String calName = calId == null ? null
            : holidayCalendars.findById(calId).map(HolidayCalendar::getName).orElse(null);
        return new UserSummaryDto(
            u.getId(), u.getEmail(), u.getDisplayName(), u.getStatus(),
            u.isMfaEnforced(), u.getAvatarData() != null, roleCodes, u.getCreatedAt(),
            calId, calName
        );
    }
}
