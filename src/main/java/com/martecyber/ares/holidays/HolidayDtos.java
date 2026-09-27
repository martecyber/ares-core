package com.martecyber.ares.holidays;

public final class HolidayDtos {
    private HolidayDtos() {}

    /** Summary used by lists (includes a day-count for quick UI feedback). */
    public record CalendarDto(Long id, String name, String description, long dayCount) {
        public static CalendarDto from(HolidayCalendar c, long dayCount) {
            return new CalendarDto(c.getId(), c.getName(), c.getDescription(), dayCount);
        }
    }

    public record CalendarDayDto(Long id, Long calendarId, String day, String label) {
        public static CalendarDayDto from(HolidayCalendarDay d) {
            return new CalendarDayDto(d.getId(), d.getCalendarId(), d.getDay().toString(), d.getLabel());
        }
    }

    public record CreateCalendarRequest(String name, String description) {}
    public record UpdateCalendarRequest(String name, String description) {}
    public record CreateDayRequest(String day, String label) {}
}
