package com.martecyber.ares.assets;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface WebEndpointHttpSampleRepository extends JpaRepository<WebEndpointHttpSample, Long> {
    List<WebEndpointHttpSample> findByAssetId(Long assetId);
    void deleteByAssetId(Long assetId);
}
