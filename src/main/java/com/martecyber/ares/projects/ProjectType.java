package com.martecyber.ares.projects;

import jakarta.persistence.*;

@Entity
@Table(name = "project_type", schema = "ares")
public class ProjectType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(nullable = false, unique = true, length = 20)
    private String code;

    @Column(name = "supertype_id")
    private Long supertypeId;

    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    @Column(nullable = false)
    private boolean disabled = false;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** Set by a plugin's own PluginLifecycle when it owns this type (e.g. "bughunting",
     *  "bughunting-hackerone") — null for every type ares-core manages itself (ASSESS/MONITOR/
     *  RETEST, and any admin-created custom subtype). Lets the frontend tell "temporarily
     *  disabled, an admin can just re-enable it" apart from "unavailable because the owning
     *  plugin isn't installed/enabled" (only the plugin should flip {@link #disabled} in that
     *  case — see ProjectTypeController#setDisabled's own guard). */
    @Column(name = "required_plugin_id", length = 100)
    private String requiredPluginId;

    /** When true, {@link ProjectService#generateProjectCode} skips the year component and uses a
     *  continuous per-org sequence instead (e.g. "ORG-BH-01") — set directly for core-owned types
     *  (MONITOR's root row) and via {@link ProjectTypeFacade#ensure} for a plugin-provided type. */
    @Column(name = "continuous_numbering", nullable = false)
    private boolean continuousNumbering = false;

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSupertypeId() { return supertypeId; }
    public void setSupertypeId(Long supertypeId) { this.supertypeId = supertypeId; }
    public boolean isSystem() { return system; }
    public void setSystem(boolean system) { this.system = system; }
    public boolean isDisabled() { return disabled; }
    public void setDisabled(boolean disabled) { this.disabled = disabled; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRequiredPluginId() { return requiredPluginId; }
    public void setRequiredPluginId(String requiredPluginId) { this.requiredPluginId = requiredPluginId; }
    public boolean isContinuousNumbering() { return continuousNumbering; }
    public void setContinuousNumbering(boolean continuousNumbering) { this.continuousNumbering = continuousNumbering; }
}
