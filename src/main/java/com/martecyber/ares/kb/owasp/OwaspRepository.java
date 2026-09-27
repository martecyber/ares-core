package com.martecyber.ares.kb.owasp;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OwaspRepository extends JpaRepository<OwaspEntry, Long>, JpaSpecificationExecutor<OwaspEntry> {

    List<OwaspEntry> findByYearOrderByRankAsc(int year);

    List<OwaspEntry> findAllByOrderByYearDescRankAsc();

    Optional<OwaspEntry> findByOwaspIdAndYear(String owaspId, int year);

    @Query("SELECT e FROM OwaspEntry e WHERE " +
        "LOWER(e.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    List<OwaspEntry> search(@Param("keyword") String keyword, Sort sort);

    @Query("SELECT e FROM OwaspEntry e WHERE e.year = :year AND (" +
        "LOWER(e.name) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
        "LOWER(e.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    List<OwaspEntry> searchByYear(@Param("keyword") String keyword, @Param("year") int year, Sort sort);

    @Query("SELECT DISTINCT e.year FROM OwaspEntry e ORDER BY e.year ASC")
    List<Integer> findDistinctYears();

    Optional<OwaspEntry> findTopByOrderBySyncedAtDesc();
}
