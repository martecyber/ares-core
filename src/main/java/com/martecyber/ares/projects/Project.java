package com.martecyber.ares.projects;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "project", schema = "ares")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(name = "type_id")
    private Long typeId;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "owner_user_id")
    private Long ownerUserId;

    @Column(name = "code", length = 60, unique = true)
    private String code;

    /**
     * Iteration cadence for MONITOR-type projects.
     * One of: weekly, biweekly, monthly, quarterly, semiannual, annual.
     * Null for non-MONITOR projects or MONITOR projects without a fixed cadence.
     */
    @Column(name = "iteration_cadence", length = 20)
    private String iterationCadence;

    /**
     * The officially "active" iteration for MONITOR projects — what new findings get
     * stamped with and what the UI shows as current. Distinct from the calendar-computed
     * label (see MonitorIterationHelper) so advancing to a new period can require
     * manual approval (see autoAdvanceIterations).
     */
    @Column(name = "active_iteration_label", length = 10)
    private String activeIterationLabel;

    /** When true, the active iteration always follows the calendar automatically
     *  (the pre-existing stateless behavior). When false (default), advancing to a new
     *  iteration requires an explicit approval action. */
    @Column(name = "auto_advance_iterations", nullable = false)
    private boolean autoAdvanceIterations = false;

    /** When true, CLIENT_USER/CLIENT_ADMIN accounts can view (read-only) this project's
     *  Detections. Off by default — an MSSP operator opts a project in explicitly. */
    @Column(name = "clients_can_view_detections", nullable = false)
    private boolean clientsCanViewDetections = false;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getTypeId() { return typeId; }
    public void setTypeId(Long typeId) { this.typeId = typeId; }

    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }

    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }

    public OffsetDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(OffsetDateTime completedAt) { this.completedAt = completedAt; }

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getIterationCadence() { return iterationCadence; }
    public void setIterationCadence(String iterationCadence) { this.iterationCadence = iterationCadence; }

    public String getActiveIterationLabel() { return activeIterationLabel; }
    public void setActiveIterationLabel(String activeIterationLabel) { this.activeIterationLabel = activeIterationLabel; }

    public boolean isAutoAdvanceIterations() { return autoAdvanceIterations; }
    public void setAutoAdvanceIterations(boolean autoAdvanceIterations) { this.autoAdvanceIterations = autoAdvanceIterations; }

    public boolean isClientsCanViewDetections() { return clientsCanViewDetections; }
    public void setClientsCanViewDetections(boolean clientsCanViewDetections) { this.clientsCanViewDetections = clientsCanViewDetections; }
}
