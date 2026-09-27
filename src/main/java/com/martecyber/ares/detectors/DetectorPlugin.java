package com.martecyber.ares.detectors;

import jakarta.persistence.*;

@Entity
@Table(name = "detector_plugin", schema = "ares",
    uniqueConstraints = @UniqueConstraint(columnNames = {"code", "tool_id"}))
public class DetectorPlugin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(name = "tool_id", nullable = false)
    private Long toolId;

    public Long getId() { return id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Long getToolId() { return toolId; }
    public void setToolId(Long toolId) { this.toolId = toolId; }
}
