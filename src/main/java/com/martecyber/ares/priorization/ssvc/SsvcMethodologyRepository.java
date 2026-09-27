package com.martecyber.ares.priorization.ssvc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SsvcMethodologyRepository extends JpaRepository<SsvcMethodology, Long> {
    @Query("SELECT m FROM SsvcMethodology m ORDER BY m.system DESC, m.name ASC")
    List<SsvcMethodology> findAllOrdered();

    Optional<SsvcMethodology> findByCode(String code);
}
