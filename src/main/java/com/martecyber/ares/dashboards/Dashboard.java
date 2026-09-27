package com.martecyber.ares.dashboards;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

/** {@code scopeId} is polymorphic depending on {@link #level} (null / org id / project id) — no
 *  FK, same convention as {@code Workflow.scopeId}. */
@Entity
@Table(name = "dashboard", schema = "ares")
public class Dashboard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DashboardLevel level;

    @Column(name = "scope_id")
    private Long scopeId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    /** A reusable, KB-managed layout — not a real scope's dashboard. Always has {@code scopeId ==
     *  null} regardless of {@link #level} (unlike a real PLATFORM dashboard, whose null scopeId
     *  means something different: "the one platform-wide scope"). See DashboardService for the
     *  query/access-check adjustments this distinction requires. */
    @Column(name = "is_template", nullable = false)
    private boolean isTemplate = false;

    @Column(columnDefinition = "text")
    private String description;

    /** Opt-in gate for DashboardPresentationService#saveItems — a dashboard must be explicitly
     *  flagged from its own edit view before it can be added to a (staff-only) SOC-screen
     *  presentation's rotation. False for every dashboard by default, template or not. */
    @Column(nullable = false)
    private boolean presentable = false;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }

    public DashboardLevel getLevel() { return level; }
    public void setLevel(DashboardLevel level) { this.level = level; }

    public Long getScopeId() { return scopeId; }
    public void setScopeId(Long scopeId) { this.scopeId = scopeId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public boolean isTemplate() { return isTemplate; }
    public void setTemplate(boolean isTemplate) { this.isTemplate = isTemplate; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isPresentable() { return presentable; }
    public void setPresentable(boolean presentable) { this.presentable = presentable; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
