package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

public interface ServiceVisibilityRepository extends JpaRepository<ServiceVisibility, Long> {

    List<ServiceVisibility> findByServiceAssetIdOrderByLastSeenDesc(Long serviceAssetId);

    /**
     * Returns an aggregate visibility state per service asset:
     *   OPEN     — every recorded vantage point sees it as OPEN
     *   CLOSED   — every recorded vantage point sees it as CLOSED
     *   FILTERED — otherwise (mixed states, or any FILTERED record)
     * Assets with no visibility records are absent from the result.
     *
     * Tuple shape: (service_asset_id BIGINT, aggregate VARCHAR)
     */
    @Query(value = """
        SELECT service_asset_id,
               CASE
                 WHEN BOOL_AND(state = 'OPEN')   THEN 'OPEN'
                 WHEN BOOL_AND(state = 'CLOSED') THEN 'CLOSED'
                 ELSE 'FILTERED'
               END
        FROM ares.service_visibility
        WHERE service_asset_id IN (:assetIds)
        GROUP BY service_asset_id
        """, nativeQuery = true)
    List<Object[]> aggregateByAssetIds(@Param("assetIds") Collection<Long> assetIds);

    /**
     * Idempotent upsert keyed by (service_asset_id, source_ip, nac_profile). Inserts a new
     * row or updates state + last_seen + last_import_id on conflict. Pass an empty string
     * (never null) for nacProfile when no NAC profile is declared.
     *
     * REQUIRES_NEW: ImportService.runImport() catches failures from this call per-asset to keep
     * one bad visibility row from failing the whole import — but on the default REQUIRED
     * propagation, catching the Java exception doesn't undo Postgres marking the shared ambient
     * transaction aborted after a failed statement (see AssetToolSightingService#upsert's doc
     * comment for the production incident this exact pattern already caused on a sibling upsert).
     * Isolating this into its own transaction keeps a failure here from poisoning the rest of the
     * import.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
        INSERT INTO ares.service_visibility
          (service_asset_id, source_ip, nac_profile, state, first_seen, last_seen, last_import_id)
        VALUES (:assetId, :sourceIp, :nacProfile, :state, NOW(), NOW(), :importId)
        ON CONFLICT (service_asset_id, source_ip, nac_profile) DO UPDATE
          SET state          = EXCLUDED.state,
              last_seen      = NOW(),
              last_import_id = EXCLUDED.last_import_id
        """, nativeQuery = true)
    int upsert(@Param("assetId")    Long   assetId,
               @Param("sourceIp")   String sourceIp,
               @Param("nacProfile") String nacProfile,
               @Param("state")      String state,
               @Param("importId")   Long   importId);
}
