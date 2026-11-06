package com.martecyber.ares.workflows;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowEntityLinksTest {

    private static final String BASE = "https://ares.example.com";

    @Test
    void projectScopedEntitiesUseProjectRoutes() {
        assertEquals(BASE + "/organization/3/projects/7/detections/42",
            WorkflowEntityLinks.build(BASE, "detection.created", 42L, 3L, 7L));
        assertEquals(BASE + "/organization/3/projects/7/findings/9",
            WorkflowEntityLinks.build(BASE, "finding.updated", 9L, 3L, 7L));
    }

    @Test
    void assetsAreOrgLevelAndProjectsGoToDashboard() {
        assertEquals(BASE + "/organization/3/assets/5",
            WorkflowEntityLinks.build(BASE, "asset.created", 5L, 3L, null));
        assertEquals(BASE + "/organization/3/projects/7/dashboard",
            WorkflowEntityLinks.build(BASE, "project.created", 7L, 3L, null));
    }

    @Test
    void platformEntities() {
        assertEquals(BASE + "/kb/cve/CVE-2024-1234",
            WorkflowEntityLinks.build(BASE, "cve.kev_added", "CVE-2024-1234", null, null));
        assertEquals(BASE + "/organization/3",
            WorkflowEntityLinks.build(BASE, "organization.created", 3L, null, null));
    }

    @Test
    void deletedOrUnroutableYieldsEmpty() {
        assertEquals("", WorkflowEntityLinks.build(BASE, "detection.deleted", 42L, 3L, 7L));
        assertEquals("", WorkflowEntityLinks.build(BASE, "detection.created", 42L, null, 7L));
    }

    @Test
    void trailingSlashStrippedAndRelativeWhenUnset() {
        assertEquals(BASE + "/organization/3/assets/5",
            WorkflowEntityLinks.build(BASE + "/", "asset.created", 5L, 3L, null));
        assertEquals("/organization/3/assets/5",
            WorkflowEntityLinks.build("", "asset.created", 5L, 3L, null));
    }
}
