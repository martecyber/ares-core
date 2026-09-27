package com.martecyber.ares.tags;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TagRepository extends JpaRepository<Tag, Long> {

    List<Tag> findByOrganizationIdOrderByNameAsc(Long organizationId);

    List<Tag> findByOrganizationIdIsNullOrderByNameAsc();

    /** Every tag an org-scoped entity (Asset/Detection/Finding) may show in its picker: its own
     *  org's tags plus every platform tag. */
    @Query("SELECT t FROM Tag t WHERE t.organizationId = :organizationId OR t.organizationId IS NULL ORDER BY t.name")
    List<Tag> findVisibleToOrganization(@Param("organizationId") Long organizationId);

    boolean existsByOrganizationIdAndNameIgnoreCase(Long organizationId, String name);

    boolean existsByOrganizationIdAndNameIgnoreCaseAndIdNot(Long organizationId, String name, Long id);

    boolean existsByOrganizationIdIsNullAndNameIgnoreCase(String name);

    boolean existsByOrganizationIdIsNullAndNameIgnoreCaseAndIdNot(String name, Long id);
}
