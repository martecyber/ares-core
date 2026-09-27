package com.martecyber.ares.holidays;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "holiday_calendar_day", schema = "ares",
       uniqueConstraints = @UniqueConstraint(columnNames = { "calendar_id", "day" }))
public class HolidayCalendarDay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "calendar_id", nullable = false)
    private Long calendarId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(length = 200)
    private String label;

    public HolidayCalendarDay() {}
    public HolidayCalendarDay(Long calendarId, LocalDate day, String label) {
        this.calendarId = calendarId;
        this.day = day;
        this.label = label;
    }

    public Long getId() { return id; }
    public Long getCalendarId() { return calendarId; }
    public void setCalendarId(Long calendarId) { this.calendarId = calendarId; }
    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
}
