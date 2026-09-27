package com.martecyber.ares.assets;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "web_endpoint_http_sample", schema = "ares")
public class WebEndpointHttpSample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "asset_id", nullable = false)
    private Long assetId;

    @Column(length = 255)
    private String label;

    @Column(name = "request_content", columnDefinition = "text")
    private String requestContent;

    @Column(name = "response_content", columnDefinition = "text")
    private String responseContent;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getAssetId() { return assetId; }
    public void setAssetId(Long assetId) { this.assetId = assetId; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getRequestContent() { return requestContent; }
    public void setRequestContent(String requestContent) { this.requestContent = requestContent; }

    public String getResponseContent() { return responseContent; }
    public void setResponseContent(String responseContent) { this.responseContent = responseContent; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
