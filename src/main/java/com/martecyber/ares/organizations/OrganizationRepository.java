package com.martecyber.ares.organizations;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface OrganizationRepository extends JpaRepository<Organization, Long> {
    Optional<Organization> findBySlugIgnoreCase(String slug);
    boolean existsBySlugIgnoreCase(String slug);
    Page<Organization> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
    Page<Organization> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Returns distinct organizations where the user has any org-scoped role assignment
     * (operator or client user, via {@code user_role} — the same table EditOrgModal's
     * "Operators"/"Client Users" sections write to), optionally filtered by status.
     */
    @Query(value = """
        SELECT DISTINCT o.* FROM ares.organization o
        WHERE (:status IS NULL OR o.status = :status)
          AND EXISTS (
            SELECT 1 FROM ares.user_role ur
            WHERE ur.organization_id = o.id
              AND ur.user_id = :userId
        )
        ORDER BY o.created_at DESC
        """,
        countQuery = """
        SELECT COUNT(DISTINCT o.id) FROM ares.organization o
        WHERE (:status IS NULL OR o.status = :status)
          AND EXISTS (
            SELECT 1 FROM ares.user_role ur
            WHERE ur.organization_id = o.id
              AND ur.user_id = :userId
        )
        """,
        nativeQuery = true)
    Page<Organization> findByOperatorUser(@Param("userId") Long userId, @Param("status") String status, Pageable pageable);
}
