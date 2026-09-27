package com.martecyber.ares.assets;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRepository extends JpaRepository<Asset, Long>, JpaSpecificationExecutor<Asset> {

    @Query(value =
        "SELECT a.* FROM ares.asset a WHERE " +
        "(:organizationId IS NULL OR a.organization_id = :organizationId) AND " +
        "(:type IS NULL OR a.type = :type) AND " +
        "(:q IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%') " +
        "ORDER BY a.created_at DESC",
        countQuery =
        "SELECT COUNT(*) FROM ares.asset a WHERE " +
        "(:organizationId IS NULL OR a.organization_id = :organizationId) AND " +
        "(:type IS NULL OR a.type = :type) AND " +
        "(:q IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%')",
        nativeQuery = true)
    Page<Asset> filter(@Param("organizationId") Long organizationId,
                       @Param("type") String type,
                       @Param("q") String q,
                       Pageable pageable);

    java.util.Optional<Asset> findByOrganizationIdAndIdentifier(Long organizationId, String identifier);

    java.util.Optional<Asset> findByOrganizationIdAndTypeAndIdentifier(Long organizationId, String type, String identifier);

    java.util.List<Asset> findByOrganizationIdAndType(Long organizationId, String type);

    long countByOrganizationIdAndType(Long organizationId, String type);

    /**
     * Returns the highest trailing sequence number already assigned to a code of
     * the form "{PREFIX}-N" for this org+type pair.  Used by generateCode() to
     * produce the next safe code even after assets have been deleted or merged.
     * REGEXP_REPLACE(code, '^.*-', '') strips everything up to the last dash,
     * leaving just the numeric suffix.
     */
    @Query(value =
        "SELECT COALESCE(MAX(CAST(REGEXP_REPLACE(code, '^.*-', '') AS INTEGER)), 0) " +
        "FROM ares.asset WHERE organization_id = :orgId AND type = :type",
        nativeQuery = true)
    long maxCodeSequenceByOrgAndType(@Param("orgId") Long orgId, @Param("type") String type);

    // Uses native SQL to avoid JPQL cross-package entity ambiguity
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT a.* FROM ares.asset a " +
        "JOIN ares.project_asset_access eaa ON eaa.asset_id = a.id " +
        "WHERE eaa.project_id = :engId " +
        "AND (:type IS NULL OR a.type = :type) " +
        "AND (:q IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%') " +
        "ORDER BY a.created_at DESC",
        countQuery =
        "SELECT COUNT(*) FROM ares.asset a " +
        "JOIN ares.project_asset_access eaa ON eaa.asset_id = a.id " +
        "WHERE eaa.project_id = :engId " +
        "AND (:type IS NULL OR a.type = :type) " +
        "AND (:q IS NULL OR a.identifier ILIKE '%' || :q || '%' OR a.metadata::text ILIKE '%' || :q || '%')",
        nativeQuery = true)
    org.springframework.data.domain.Page<Asset> filterByProject(
        @Param("engId") Long engId,
        @Param("type")  String type,
        @Param("q") String q,
        org.springframework.data.domain.Pageable pageable);

    /**
     * Same as {@link #filterByProject} but excludes assets the operator (or the
     * scope classifier) has marked out-of-scope. Used by the agent-task target
     * resolver — operators must never accidentally scan assets they've flagged
     * as out-of-scope for the project.
     */
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT a.* FROM ares.asset a " +
        "JOIN ares.project_asset_access eaa ON eaa.asset_id = a.id " +
        "WHERE eaa.project_id = :engId " +
        "AND eaa.scope_status <> 'out_of_scope' " +
        "AND (:type IS NULL OR a.type = :type) " +
        "ORDER BY a.created_at DESC",
        nativeQuery = true)
    org.springframework.data.domain.Page<Asset> filterByProjectInScope(
        @Param("engId") Long engId,
        @Param("type")  String type,
        org.springframework.data.domain.Pageable pageable);

    /** Like {@link #filterByProjectInScope} but requires {@code scope_status = 'in_scope'} — no unknowns. */
    @org.springframework.data.jpa.repository.Query(value =
        "SELECT a.* FROM ares.asset a " +
        "JOIN ares.project_asset_access eaa ON eaa.asset_id = a.id " +
        "WHERE eaa.project_id = :engId " +
        "AND eaa.scope_status = 'in_scope' " +
        "AND (:type IS NULL OR a.type = :type) " +
        "ORDER BY a.created_at DESC",
        nativeQuery = true)
    org.springframework.data.domain.Page<Asset> filterByProjectStrictInScope(
        @Param("engId") Long engId,
        @Param("type")  String type,
        org.springframework.data.domain.Pageable pageable);

    /**
     * For each given SERVICE asset id, traverses the chain
     *   service ← (interface_service) ← interface ← (host_interface) ← host
     * and returns the owning host. Services without a complete chain are absent
     * from the result.
     *
     * Tuple shape: (service_id BIGINT, host_id BIGINT, host_identifier TEXT, host_code TEXT)
     */
    /**
     * Bulk delete by ID list. More efficient than JPA's deleteAllById() which issues
     * N individual DELETEs. Callers must ensure cascades are handled before this call.
     */
    @org.springframework.data.jpa.repository.Modifying
    @Query("DELETE FROM Asset a WHERE a.id IN :ids")
    void deleteByIdIn(@Param("ids") java.util.Collection<Long> ids);

    /**
     * Returns assets (id, identifier, type, code) that would become isolated
     * (no remaining relationships) if the given asset IDs were deleted.
     *
     * An asset is "orphaned" if ALL of its current relationships involve at
     * least one of the assets being deleted and NONE involve an asset outside
     * the deletion set.
     */
    @Query(value = """
        SELECT a.id, a.identifier, a.type, a.code
        FROM ares.asset a
        WHERE a.id NOT IN (:ids)
          AND EXISTS (
            SELECT 1 FROM ares.asset_relationships ar
            WHERE (ar.from_asset_id = a.id OR ar.to_asset_id = a.id)
              AND (ar.from_asset_id IN (:ids) OR ar.to_asset_id IN (:ids))
          )
          AND NOT EXISTS (
            SELECT 1 FROM ares.asset_relationships ar2
            WHERE (ar2.from_asset_id = a.id OR ar2.to_asset_id = a.id)
              AND ar2.from_asset_id NOT IN (:ids)
              AND ar2.to_asset_id NOT IN (:ids)
          )
        ORDER BY a.type, a.identifier
        """, nativeQuery = true)
    java.util.List<Object[]> findOrphansIfDeleted(@Param("ids") java.util.Collection<Long> ids);

    @Query(value = """
        SELECT svc.id, host.id, host.identifier, host.code
        FROM ares.asset svc
        JOIN ares.asset_relationships r_if_svc
          ON r_if_svc.to_asset_id = svc.id AND r_if_svc.type = 'interface_service'
        JOIN ares.asset iface
          ON iface.id = r_if_svc.from_asset_id AND iface.type = 'interface'
        JOIN ares.asset_relationships r_host_if
          ON r_host_if.to_asset_id = iface.id AND r_host_if.type = 'host_interface'
        JOIN ares.asset host
          ON host.id = r_host_if.from_asset_id AND host.type = 'host'
        WHERE svc.id IN (:serviceIds)
        """, nativeQuery = true)
    java.util.List<Object[]> findHostsForServices(@Param("serviceIds") java.util.Collection<Long> serviceIds);
}
