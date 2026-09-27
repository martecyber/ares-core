package com.martecyber.ares.aql.materialize;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface KbMaterializedRefRepository extends JpaRepository<KbMaterializedRef, KbMaterializedRefId> {
    List<KbMaterializedRef> findByIdCatalogId(Long catalogId);
}
