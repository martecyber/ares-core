package com.martecyber.ares.references;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReferenceCatalogRepository extends JpaRepository<ReferenceCatalog, Long> {
    Optional<ReferenceCatalog> findByCode(String code);
}
