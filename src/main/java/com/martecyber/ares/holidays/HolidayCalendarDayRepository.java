package com.martecyber.ares.holidays;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface HolidayCalendarDayRepository extends JpaRepository<HolidayCalendarDay, Long> {

    List<HolidayCalendarDay> findByCalendarIdOrderByDayAsc(Long calendarId);

    @Query("SELECT d FROM HolidayCalendarDay d WHERE d.calendarId = :calendarId AND d.day BETWEEN :start AND :end ORDER BY d.day")
    List<HolidayCalendarDay> findByCalendarIdAndRange(@Param("calendarId") Long calendarId,
                                                      @Param("start") LocalDate start,
                                                      @Param("end") LocalDate end);

    long countByCalendarId(Long calendarId);
}
