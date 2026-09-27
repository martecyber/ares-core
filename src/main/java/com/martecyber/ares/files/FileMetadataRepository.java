package com.martecyber.ares.files;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

    @Query("""
        SELECT f FROM FileMetadata f WHERE
            (:orgId        IS NULL OR f.organizationId = :orgId)  AND
            (:orgIds       IS NULL OR f.organizationId IN :orgIds) AND
            (:projectId IS NULL OR f.projectId   = :projectId) AND
            (:findingId    IS NULL OR f.findingId       = :findingId)
        ORDER BY f.createdAt DESC
        """)
    Page<FileMetadata> filter(
        @Param("orgId")        Long organizationId,
        @Param("orgIds")       java.util.Collection<Long> organizationIds,
        @Param("projectId") Long projectId,
        @Param("findingId")    Long findingId,
        Pageable pageable
    );
}
