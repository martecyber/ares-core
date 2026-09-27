package com.martecyber.ares.priorization.ssvc;

import jakarta.persistence.*;

@Entity
@Table(name = "ssvc_tree_node_option", schema = "ares")
public class SsvcTreeNodeOption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tree_node_id", nullable = false)
    private Long treeNodeId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String label;

    @Column(name = "help_text", columnDefinition = "text")
    private String helpText;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public Long getId() { return id; }

    public Long getTreeNodeId() { return treeNodeId; }
    public void setTreeNodeId(Long treeNodeId) { this.treeNodeId = treeNodeId; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getHelpText() { return helpText; }
    public void setHelpText(String helpText) { this.helpText = helpText; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
