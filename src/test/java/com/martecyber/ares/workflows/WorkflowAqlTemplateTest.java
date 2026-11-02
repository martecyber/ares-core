package com.martecyber.ares.workflows;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowAqlTemplateTest {

    @Test
    void workflowPlaceholdersRenderAndAqlVariablesSurvive() {
        Map<String, Object> ctx = Map.of("trigger.finding.title", "XSS", "variables.limit", 5);
        String aql = "title == \"{{trigger.finding.title}}\" AND created > {{now}}-7d "
            + "AND status.closed < {{ current_iteration_start }}-1i AND createdAt < {{now}}-{{p0_sla_period}}";
        assertEquals("title == \"XSS\" AND created > {{now}}-7d "
            + "AND status.closed < {{current_iteration_start}}-1i AND createdAt < {{now}}-{{p0_sla_period}}",
            WorkflowAqlTemplate.render(aql, ctx));
    }

    @Test
    void unknownWorkflowPlaceholdersAreStillBlanked() {
        assertEquals("title == \"\"", WorkflowAqlTemplate.render("title == \"{{trigger.missing}}\"", Map.of()));
        assertEquals("", WorkflowAqlTemplate.render(null, Map.of()));
    }

    @Test
    void triggerScopeComesFromTheTriggeringEntity() {
        Map<String, Object> ctx = Map.of("trigger.entityId", 7, "trigger.finding.projectId", 42, "trigger.asset.organizationId", 3);
        assertEquals(42L, WorkflowAqlTemplate.triggerScopeId(ctx, "projectId"));
        assertEquals(3L, WorkflowAqlTemplate.triggerScopeId(ctx, "organizationId"));
        assertNull(WorkflowAqlTemplate.triggerScopeId(Map.of(), "projectId"));
    }
}
