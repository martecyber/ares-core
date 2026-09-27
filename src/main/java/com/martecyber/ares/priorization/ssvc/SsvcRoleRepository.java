package com.martecyber.ares.priorization.ssvc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SsvcRoleRepository extends JpaRepository<SsvcRole, Long> {
    @Query("SELECT r FROM SsvcRole r WHERE r.methodologyId = :methodologyId ORDER BY r.sortOrder ASC, r.name ASC")
    List<SsvcRole> findByMethodologyId(@Param("methodologyId") Long methodologyId);

    void deleteByMethodologyId(Long methodologyId);
}
