package com.martecyber.ares.assets;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import com.martecyber.ares.tags.TagDto;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "asset", schema = "ares",
    uniqueConstraints = @UniqueConstraint(columnNames = {"organization_id", "code"}))
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(name = "organization_id", nullable = false)
    private Long organizationId;

    @Column(nullable = false, length = 50)
    private String type;

    @Column(nullable = false, length = 255)
    private String identifier;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    /** Sub-classification when type == "host" (pc, server, vm, router…). NULL for other types. */
    @Column(name = "host_subtype", length = 20)
    private String hostSubtype;

    /** Only meaningful when type == "host". Ordered list of every hostname ever observed
     *  for this host — tools only ever append to it; index 0 is the "primary" hostname. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<String> hostnames = new ArrayList<>();

    /** Only meaningful when type == "host". When false, {@code identifier} ("Name") is
     *  auto-derived from {@code hostnames} (see AssetService#recomputeHostName); when
     *  true, a user has explicitly pinned the Name and it is never recomputed. */
    @Column(name = "name_override", nullable = false)
    private boolean nameOverride;

    /** Populated at read-time by AssetService for org-level lists. Not persisted. */
    @jakarta.persistence.Transient
    private boolean thirdParty;

    /** Populated at read-time by AssetService. Not persisted — see asset_tag. */
    @jakarta.persistence.Transient
    private List<TagDto> tags = new ArrayList<>();

    public Long getId() { return id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long organizationId) { this.organizationId = organizationId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getIdentifier() { return identifier; }
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public String getHostSubtype() { return hostSubtype; }
    public void setHostSubtype(String hostSubtype) { this.hostSubtype = hostSubtype; }

    public List<String> getHostnames() { return hostnames; }
    public void setHostnames(List<String> hostnames) { this.hostnames = hostnames != null ? hostnames : new ArrayList<>(); }

    public boolean isNameOverride() { return nameOverride; }
    public void setNameOverride(boolean nameOverride) { this.nameOverride = nameOverride; }

    public boolean isThirdParty() { return thirdParty; }
    public void setThirdParty(boolean thirdParty) { this.thirdParty = thirdParty; }

    public List<TagDto> getTags() { return tags; }
    public void setTags(List<TagDto> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
}
