package com.martecyber.ares.aql.registry;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {

    /**
     * Platform-wide definitions (organizationId IS NULL) for an entity, across all asset types.
     * Callers wanting the effective set for one org should fetch this plus
     * {@link #findByEntityTypeAndOrganizationId} and let org rows override same-key platform
     * rows in application code — an "IN" list containing null wouldn't match NULL columns in SQL,
     * so the two scopes are queried separately rather than merged in one derived query.
     */
    List<FieldDefinition> findByEntityTypeAndOrganizationIdIsNullOrderByAssetTypeAscSortOrderAsc(String entityType);

    List<FieldDefinition> findByEntityTypeAndOrganizationIdOrderByAssetTypeAscSortOrderAsc(
        String entityType, Long organizationId);

    boolean existsByOrganizationIdAndEntityTypeAndAssetTypeAndFieldKey(
        Long organizationId, String entityType, String assetType, String fieldKey);
}
