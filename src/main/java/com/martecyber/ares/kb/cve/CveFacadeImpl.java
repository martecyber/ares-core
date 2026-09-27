package com.martecyber.ares.kb.cve;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** Thin adapter exposing {@link CveRepository} to plugins as the {@code ares-sdk}-owned {@link
 *  CveFacade}. */
@Component
class CveFacadeImpl implements CveFacade {

    private final CveRepository cveRepository;

    CveFacadeImpl(CveRepository cveRepository) {
        this.cveRepository = cveRepository;
    }

    @Override
    public List<CveInfo> findByCveIds(Collection<String> cveIds) {
        return cveRepository.findAllByCveIdIn(cveIds).stream()
            .map(e -> new CveInfo(e.getCveId(), e.getSeverity(), e.getCvssScore(), e.getCvssVector(), e.getCvssVersion()))
            .toList();
    }
}
