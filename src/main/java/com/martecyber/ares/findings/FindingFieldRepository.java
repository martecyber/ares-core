package com.martecyber.ares.findings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FindingFieldRepository extends JpaRepository<FindingField, Long> {

    /** Ordered by the field type's configured sort_order (admin-defined), not insertion order. */
    @Query("""
        SELECT f FROM FindingField f
        JOIN FindingFieldType t ON t.id = f.typeId
        WHERE f.findingId = :findingId
        ORDER BY t.sortOrder ASC, t.title ASC
        """)
    List<FindingField> findByFindingId(@Param("findingId") Long findingId);
}
