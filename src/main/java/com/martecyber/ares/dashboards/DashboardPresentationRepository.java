package com.martecyber.ares.dashboards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardPresentationRepository extends JpaRepository<DashboardPresentation, Long> {

    List<DashboardPresentation> findAllByOrderByNameAsc();
}
