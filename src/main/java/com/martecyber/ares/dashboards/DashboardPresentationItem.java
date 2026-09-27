package com.martecyber.ares.dashboards;

import jakarta.persistence.*;

/** {@code dashboardId} is a real FK straight to {@code ares.dashboard(id)} — unlike {@link
 *  Dashboard#getScopeId()}'s deliberately polymorphic no-FK convention, an item always points at
 *  one concrete dashboard row regardless of that dashboard's own level, so a presentation can
 *  freely mix platform/organization/project dashboards with no level-specific handling anywhere
 *  in this class. */
@Entity
@Table(name = "dashboard_presentation_item", schema = "ares")
public class DashboardPresentationItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "presentation_id", nullable = false)
    private Long presentationId;

    @Column(name = "dashboard_id", nullable = false)
    private Long dashboardId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public Long getId() { return id; }

    public Long getPresentationId() { return presentationId; }
    public void setPresentationId(Long presentationId) { this.presentationId = presentationId; }

    public Long getDashboardId() { return dashboardId; }
    public void setDashboardId(Long dashboardId) { this.dashboardId = dashboardId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
