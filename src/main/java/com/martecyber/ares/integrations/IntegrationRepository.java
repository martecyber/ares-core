package com.martecyber.ares.integrations;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IntegrationRepository extends JpaRepository<Integration, Long> {

    @Query("SELECT i FROM Integration i WHERE " +
           "(:organizationId IS NULL OR i.organizationId = :organizationId) AND " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:status IS NULL OR i.status = :status) " +
           "ORDER BY i.createdAt DESC")
    Page<Integration> filter(@Param("organizationId") Long organizationId,
                             @Param("type") String type,
                             @Param("status") String status,
                             Pageable pageable);

    /** Same as {@link #filter}, limited to platform-wide integrations plus those of {@code orgIds}. */
    @Query("SELECT i FROM Integration i WHERE " +
           "(:organizationId IS NULL OR i.organizationId = :organizationId) AND " +
           "(:type IS NULL OR i.type = :type) AND " +
           "(:status IS NULL OR i.status = :status) AND " +
           "(i.organizationId IS NULL OR i.organizationId IN :orgIds) " +
           "ORDER BY i.createdAt DESC")
    Page<Integration> filterVisible(@Param("organizationId") Long organizationId,
                                    @Param("type") String type,
                                    @Param("status") String status,
                                    @Param("orgIds") java.util.Collection<Long> orgIds,
                                    Pageable pageable);
}
