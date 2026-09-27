package com.martecyber.ares.holidays;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.holidays.HolidayDtos.*;
import com.martecyber.ares.users.UserRepository;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class HolidayCalendarService {

    private final HolidayCalendarRepository calendars;
    private final HolidayCalendarDayRepository days;
    private final UserRepository users;

    public HolidayCalendarService(HolidayCalendarRepository calendars,
                                  HolidayCalendarDayRepository days,
                                  UserRepository users) {
        this.calendars = calendars;
        this.days = days;
        this.users = users;
    }

    // ── Calendars (admin) ─────────────────────────────────────────

    public List<CalendarDto> list() {
        return calendars.findAllByOrderByNameAsc().stream()
            .map(c -> CalendarDto.from(c, days.countByCalendarId(c.getId())))
            .toList();
    }

    public CalendarDto get(Long id) {
        HolidayCalendar c = load(id);
        return CalendarDto.from(c, days.countByCalendarId(id));
    }

    @Transactional
    public CalendarDto create(CreateCalendarRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        HolidayCalendar c = new HolidayCalendar(req.name().trim(), trim(req.description()));
        return CalendarDto.from(calendars.save(c), 0);
    }

    @Transactional
    public CalendarDto update(Long id, UpdateCalendarRequest req) {
        HolidayCalendar c = load(id);
        if (req.name() != null && !req.name().isBlank()) c.setName(req.name().trim());
        if (req.description() != null) c.setDescription(trim(req.description()));
        return CalendarDto.from(calendars.save(c), days.countByCalendarId(id));
    }

    @Transactional
    public void delete(Long id) {
        if (!calendars.existsById(id)) throw NotFoundException.of("holiday calendar", id);
        calendars.deleteById(id);
    }

    // ── Days (admin) ──────────────────────────────────────────────

    public List<CalendarDayDto> listDays(Long calendarId) {
        load(calendarId);
        return days.findByCalendarIdOrderByDayAsc(calendarId).stream()
            .map(CalendarDayDto::from).toList();
    }

    @Transactional
    public CalendarDayDto addDay(Long calendarId, CreateDayRequest req) {
        load(calendarId);
        if (req == null || req.day() == null || req.day().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "day is required (yyyy-MM-dd)");
        }
        LocalDate parsed;
        try { parsed = LocalDate.parse(req.day()); }
        catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "day must be in yyyy-MM-dd format");
        }
        // The unique constraint on (calendar_id, day) is the source of truth; surface
        // a friendly 409 instead of a generic server error if the operator dupes.
        HolidayCalendarDay d = new HolidayCalendarDay(calendarId, parsed, trim(req.label()));
        try {
            return CalendarDayDto.from(days.save(d));
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                parsed + " is already in this calendar");
        }
    }

    @Transactional
    public void deleteDay(Long calendarId, Long dayId) {
        HolidayCalendarDay d = days.findById(dayId)
            .orElseThrow(() -> NotFoundException.of("holiday day", dayId));
        if (!d.getCalendarId().equals(calendarId))
            throw NotFoundException.of("holiday day", dayId);
        days.deleteById(dayId);
    }

    // ── Per-user resolution (used by ProfileService) ──────────────

    /**
     * Returns the holiday days that fall in [start, end] for whatever calendar the
     * given user has opted into. Empty list when the user has no calendar set.
     */
    public List<CalendarDayDto> forUserInRange(Long userId, LocalDate start, LocalDate end) {
        Long calId = users.findById(userId).map(u -> u.getHolidayCalendarId()).orElse(null);
        if (calId == null) return List.of();
        return days.findByCalendarIdAndRange(calId, start, end).stream()
            .map(CalendarDayDto::from).toList();
    }

    private HolidayCalendar load(Long id) {
        return calendars.findById(id)
            .orElseThrow(() -> NotFoundException.of("holiday calendar", id));
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
