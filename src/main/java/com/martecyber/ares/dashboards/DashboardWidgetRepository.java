package com.martecyber.ares.dashboards;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DashboardWidgetRepository extends JpaRepository<DashboardWidget, Long> {

    List<DashboardWidget> findByDashboardId(Long dashboardId);

    void deleteByDashboardId(Long dashboardId);
}
