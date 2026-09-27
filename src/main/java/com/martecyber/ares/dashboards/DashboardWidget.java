package com.martecyber.ares.dashboards;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "dashboard_widget", schema = "ares")
public class DashboardWidget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dashboard_id", nullable = false)
    private Long dashboardId;

    /** Stored as a raw string, not {@code @Enumerated(EnumType.STRING)} — that mapping makes
     *  Hibernate throw {@code IllegalArgumentException} hydrating ANY row whose value isn't a
     *  current {@link DashboardWidgetType} constant, which aborts the *entire* query (every other
     *  widget on the dashboard too, not just the bad row) the moment a widget type is ever
     *  renamed/removed and a pre-existing row still carries the old name (see V170's cleanup —
     *  this is the same class of incident, made non-fatal instead of requiring a migration every
     *  time). {@link #getType()} parses leniently and returns null for an unrecognized value;
     *  {@link #getTypeName()} exposes the raw string either way so the caller can still show
     *  something (an "unsupported widget" placeholder) instead of just dropping the row. */
    @Column(nullable = false, length = 40)
    private String type;

    @Column(length = 150)
    private String title;

    /** Type-specific: {@code {entity, aql}} for AQL_COUNT, {@code {entity, aql, groupByField,
     *  chartType}} for AQL_CHART, mostly empty for bespoke types (a few take a small {@code
     *  {limit}}). Raw JSON text, same storage convention as {@code Workflow.graphDefinition}/node
     *  configs — parsed with Jackson at the DTO boundary, not mapped to a Java shape here. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String config = "{}";

    @Column(name = "pos_x", nullable = false)
    private int posX = 0;

    @Column(name = "pos_y", nullable = false)
    private int posY = 0;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public Long getId() { return id; }

    public Long getDashboardId() { return dashboardId; }
    public void setDashboardId(Long dashboardId) { this.dashboardId = dashboardId; }

    /** Null when {@code type} isn't a current {@link DashboardWidgetType} constant (a widget type
     *  renamed/removed after this row was seeded) — callers must handle null as "can't render/
     *  execute this widget" rather than assuming it's always a recognized type. */
    public DashboardWidgetType getType() {
        if (type == null) return null;
        try { return DashboardWidgetType.valueOf(type); }
        catch (IllegalArgumentException e) { return null; }
    }
    public void setType(DashboardWidgetType type) { this.type = type.name(); }

    /** The raw stored type name, even when {@link #getType()} can't parse it — for surfacing a
     *  meaningful "unsupported widget: X" placeholder instead of silently dropping the widget. */
    public String getTypeName() { return type; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getConfig() { return config; }
    public void setConfig(String config) { this.config = config; }

    public int getPosX() { return posX; }
    public void setPosX(int posX) { this.posX = posX; }

    public int getPosY() { return posY; }
    public void setPosY(int posY) { this.posY = posY; }

    public int getWidth() { return width; }
    public void setWidth(int width) { this.width = width; }

    public int getHeight() { return height; }
    public void setHeight(int height) { this.height = height; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
