package com.martecyber.ares.holidays;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HolidayCalendarRepository extends JpaRepository<HolidayCalendar, Long> {
    List<HolidayCalendar> findAllByOrderByNameAsc();
}
