package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "service_visibility", schema = "ares")
public class ServiceVisibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "service_asset_id", nullable = false)
    private Long serviceAssetId;

    @Column(name = "source_ip", nullable = false, length = 45)
    private String sourceIp;

    /** Free-text NAC profile tag; empty string = no NAC profile (NOT NULL to keep unique constraint sane). */
    @Column(name = "nac_profile", nullable = false, length = 64)
    private String nacProfile = "";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ServiceVisibilityState state;

    @Column(name = "first_seen", nullable = false)
    private OffsetDateTime firstSeen;

    @Column(name = "last_seen", nullable = false)
    private OffsetDateTime lastSeen;

    @Column(name = "last_import_id")
    private Long lastImportId;

    public Long getId() { return id; }

    public Long getServiceAssetId() { return serviceAssetId; }
    public void setServiceAssetId(Long v) { this.serviceAssetId = v; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String v) { this.sourceIp = v; }

    public String getNacProfile() { return nacProfile; }
    public void setNacProfile(String v) { this.nacProfile = v == null ? "" : v; }

    public ServiceVisibilityState getState() { return state; }
    public void setState(ServiceVisibilityState v) { this.state = v; }

    public OffsetDateTime getFirstSeen() { return firstSeen; }
    public void setFirstSeen(OffsetDateTime v) { this.firstSeen = v; }

    public OffsetDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(OffsetDateTime v) { this.lastSeen = v; }

    public Long getLastImportId() { return lastImportId; }
    public void setLastImportId(Long v) { this.lastImportId = v; }
}
