package com.martecyber.ares.priorization.ssvc;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "ssvc_tree_node", schema = "ares")
public class SsvcTreeNode {

    public static final String TYPE_BRANCH = "branch";
    public static final String TYPE_LEAF = "leaf";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "role_id", nullable = false)
    private Long roleId;

    @Column(name = "parent_node_id")
    private Long parentNodeId;

    /** Which option of the parent branch node leads here. Null for the tree's root. */
    @Column(name = "parent_option_code", length = 50)
    private String parentOptionCode;

    @Column(name = "node_type", nullable = false, length = 10)
    private String nodeType;

    // ── branch-only ──
    @Column(name = "decision_point_code", length = 50)
    private String decisionPointCode;

    @Column(name = "decision_point_name", length = 150)
    private String decisionPointName;

    @Column(name = "decision_point_help", columnDefinition = "text")
    private String decisionPointHelp;

    // ── leaf-only ──
    @Column(name = "outcome_code", length = 50)
    private String outcomeCode;

    @Column(name = "outcome_label", length = 150)
    private String outcomeLabel;

    @Column(name = "outcome_description", columnDefinition = "text")
    private String outcomeDescription;

    /** 'P0'..'P4', only set when the role's usesPriorityMapping is true. */
    @Column(name = "priority_level", length = 2)
    private String priorityLevel;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public Long getId() { return id; }

    public Long getRoleId() { return roleId; }
    public void setRoleId(Long roleId) { this.roleId = roleId; }

    public Long getParentNodeId() { return parentNodeId; }
    public void setParentNodeId(Long parentNodeId) { this.parentNodeId = parentNodeId; }

    public String getParentOptionCode() { return parentOptionCode; }
    public void setParentOptionCode(String parentOptionCode) { this.parentOptionCode = parentOptionCode; }

    public String getNodeType() { return nodeType; }
    public void setNodeType(String nodeType) { this.nodeType = nodeType; }
    public boolean isBranch() { return TYPE_BRANCH.equals(nodeType); }
    public boolean isLeaf() { return TYPE_LEAF.equals(nodeType); }

    public String getDecisionPointCode() { return decisionPointCode; }
    public void setDecisionPointCode(String decisionPointCode) { this.decisionPointCode = decisionPointCode; }

    public String getDecisionPointName() { return decisionPointName; }
    public void setDecisionPointName(String decisionPointName) { this.decisionPointName = decisionPointName; }

    public String getDecisionPointHelp() { return decisionPointHelp; }
    public void setDecisionPointHelp(String decisionPointHelp) { this.decisionPointHelp = decisionPointHelp; }

    public String getOutcomeCode() { return outcomeCode; }
    public void setOutcomeCode(String outcomeCode) { this.outcomeCode = outcomeCode; }

    public String getOutcomeLabel() { return outcomeLabel; }
    public void setOutcomeLabel(String outcomeLabel) { this.outcomeLabel = outcomeLabel; }

    public String getOutcomeDescription() { return outcomeDescription; }
    public void setOutcomeDescription(String outcomeDescription) { this.outcomeDescription = outcomeDescription; }

    public String getPriorityLevel() { return priorityLevel; }
    public void setPriorityLevel(String priorityLevel) { this.priorityLevel = priorityLevel; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }

    public OffsetDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
