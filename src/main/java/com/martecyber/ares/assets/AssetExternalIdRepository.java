package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AssetExternalIdRepository extends JpaRepository<AssetExternalId, Long> {
    Optional<AssetExternalId> findByToolAndExternalId(String tool, String externalId);
    List<AssetExternalId> findByAssetId(Long assetId);
}
