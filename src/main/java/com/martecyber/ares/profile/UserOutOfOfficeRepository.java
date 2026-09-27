package com.martecyber.ares.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface UserOutOfOfficeRepository extends JpaRepository<UserOutOfOffice, Long> {

    List<UserOutOfOffice> findByUserIdOrderByStartDateAsc(Long userId);

    @Query("SELECT o FROM UserOutOfOffice o WHERE o.userId = :userId AND o.startDate <= :end AND o.endDate >= :start ORDER BY o.startDate")
    List<UserOutOfOffice> findByUserIdAndRange(@Param("userId") Long userId, @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("SELECT o FROM UserOutOfOffice o WHERE o.startDate <= :end AND o.endDate >= :start ORDER BY o.startDate")
    List<UserOutOfOffice> findByRange(@Param("start") LocalDate start, @Param("end") LocalDate end);
}
