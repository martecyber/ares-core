package com.martecyber.ares.imports;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ScanImportChangeRepository extends JpaRepository<ScanImportChange, Long> {

    List<ScanImportChange> findByScanImportIdAndRevertedAtIsNullOrderByIdAsc(Long scanImportId);

    boolean existsByScanImportIdAndRevertedAtIsNull(Long scanImportId);

    /** Used to block reverting a row that a later, still-live import has since touched again. */
    @Query("SELECT COUNT(c) > 0 FROM ScanImportChange c " +
           "WHERE c.entityType = :entityType AND c.entityId = :entityId " +
           "AND c.createdAt > :after AND c.revertedAt IS NULL")
    boolean existsNewerUnrevertedChange(
        @Param("entityType") String entityType,
        @Param("entityId") Long entityId,
        @Param("after") OffsetDateTime after);
}
