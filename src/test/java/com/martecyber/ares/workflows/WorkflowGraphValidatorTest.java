package com.martecyber.ares.workflows;

import com.martecyber.ares.agents.tasks.AgentToolSpecRegistry;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.aql.registry.AqlFieldNotFoundException;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor;
import com.martecyber.ares.workflows.integrations.IntegrationActionHandler;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import com.martecyber.ares.workflows.integrations.IntegrationActionResult;
import com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkflowGraphValidatorTest {

    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE);
    private static final Set<AqlOperator> BOOLEAN_OPS = EnumSet.of(AqlOperator.EQ, AqlOperator.NEQ);

    private static final class FixtureRegistry implements EntityAqlRegistry<Detection> {
        private final Map<String, AqlField<Detection>> fields = new LinkedHashMap<>();
        FixtureRegistry() {
            fields.put("priority", new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.PHYSICAL_COLUMN,
                NUMBER_OPS, r -> r.get("priority"), AqlField.PRIORITY_LABELS, (java.util.function.Function<Detection, Object>) Detection::getPriority));
            fields.put("kevListed", new KbMaterializedField<>("kevListed", AqlFieldType.BOOLEAN, BOOLEAN_OPS,
                1L, "detections", "kevListed"));
        }
        @Override public String entityName() { return "detection"; }
        @Override public Optional<AqlField<Detection>> field(String name) { return Optional.ofNullable(fields.get(name)); }
        @Override public List<AqlField<Detection>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Detection>> allFields() { return List.copyOf(fields.values()); }
    }

    /** A second, distinct entity — enough to exercise ASSIGN_VARIABLE's cross-entity rules
     *  (mismatched entityType-vs-variableType, scalar field projection). The wrapped Java type
     *  doesn't matter for pure validation (never executed against a real DB in this test class). */
    private static final class FixtureCveRegistry implements EntityAqlRegistry<Detection> {
        private final Map<String, AqlField<Detection>> fields = new LinkedHashMap<>();
        FixtureCveRegistry() {
            fields.put("name", new ColumnAqlField<>("name", AqlFieldType.STRING, AqlFieldKind.PHYSICAL_COLUMN,
                Set.of(AqlOperator.EQ), r -> r.get("name")));
        }
        @Override public String entityName() { return "cve"; }
        @Override public Optional<AqlField<Detection>> field(String name) { return Optional.ofNullable(fields.get(name)); }
        @Override public List<AqlField<Detection>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Detection>> allFields() { return List.copyOf(fields.values()); }
    }

    /** Registers 'asset' as a recognized ASSIGN_VARIABLE variableType — needed for
     *  ACTION_AGENT_TASK's workflow_variable targetsFrom tests, which require an asset-typed
     *  variable to point at. No fields needed: this class only ever exercises the name-recognition
     *  check ({@code aqlRegistries.require("asset")}), never a real query against it. */
    private static final class FixtureAssetRegistry implements EntityAqlRegistry<Object> {
        @Override public String entityName() { return "asset"; }
        @Override public Optional<AqlField<Object>> field(String name) { return Optional.empty(); }
        @Override public List<AqlField<Object>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Object>> allFields() { return List.of(); }
    }

    private static final class FixtureActionHandler implements IntegrationActionHandler {
        @Override public String integrationType() { return "fixture-type"; }
        @Override public String integrationTypeLabel() { return "Fixture"; }
        @Override public java.util.Set<String> supportedScopes() { return java.util.Set.of("project"); }
        @Override public List<IntegrationActionDescriptor> describeActions() {
            return List.of(new IntegrationActionDescriptor("FIXTURE_ACTION", "Fixture action"));
        }
        @Override public List<IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) { return List.of(); }
        @Override public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) { return 1L; }
        @Override public IntegrationActionResult checkStatus(Long refId) { return new IntegrationActionResult(IntegrationActionResult.COMPLETED, "{}", null); }
    }

    private final com.martecyber.ares.agents.pools.AgentPoolMemberRepository agentPoolMemberRepo =
        mock(com.martecyber.ares.agents.pools.AgentPoolMemberRepository.class);
    private final com.martecyber.ares.agents.AgentRepository agentRepo =
        mock(com.martecyber.ares.agents.AgentRepository.class);

    /** Registered below with two synthetic fixture specs — NOT modeled on any real plugin's
     *  tool — so this suite never encodes a real plugin's actual field/flag shape into ares-core.
     *  'test-tool' is a permissive, unlimited-target spec (targets-only, no other required
     *  fields); 'single-target-tool' declares maxTargets: 1 to exercise the hard batchSize
     *  check. AgentToolSpecRegistry is only populated at runtime by an installed plugin
     *  otherwise. */
    private final AgentToolSpecRegistry agentToolSpecRegistry = new AgentToolSpecRegistry();

    private final WorkflowGraphValidator validator =
        new WorkflowGraphValidator(new AqlRegistryLookup(List.of(new FixtureRegistry(), new FixtureCveRegistry(), new FixtureAssetRegistry())), agentToolSpecRegistry,
            new IntegrationActionRegistry(List.of(new FixtureActionHandler()), mock(com.martecyber.ares.plugins.PluginRepository.class)),
            agentPoolMemberRepo, agentRepo);

    {
        agentToolSpecRegistry.register(new com.martecyber.ares.agents.tasks.AgentToolSpec(
            "test-tool", "Test Tool", "test-tool", "default", Set.of(), Set.of(), null, "positional",
            null, null, null, false, null, List.of()), null);
        // Synthetic maxTargets: 1 fixture — exercises the hard, save-time batchSize check
        // (WorkflowGraphValidator turns "this tool refuses more than one target" into "the
        // node's own batchSize must be exactly maxTargets"). Not modeled on any real tool.
        agentToolSpecRegistry.register(new com.martecyber.ares.agents.tasks.AgentToolSpec(
            "single-target-tool", "Single Target Tool", "single-target-tool", "default", Set.of(), Set.of(), 1, "single-flag",
            "--target", null, null, false, null, List.of()), null);
    }

    private String graph(String nodesAndEdgesJson) {
        return "{" + nodesAndEdgesJson + "}";
    }

    @Test
    void validGraphWithTriggerConditionAndAction() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void notificationAcceptsAKnownSeverity() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1,"severity":"critical"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void notificationOmittedSeverityIsFine() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void notificationRejectsAnUnknownSeverity() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1,"severity":"urgent"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("severity"));
    }

    @Test
    void reportFindingDocumentModeRequiresReportTemplateId() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"r","config":{"findingId":"{{trigger.entityId}}","mode":"document"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("reportTemplateId"));
    }

    @Test
    void reportFindingDocumentModeWithTemplateIdIsFine() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"r","config":{"findingId":"{{trigger.entityId}}","mode":"document","reportTemplateId":7}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void reportFindingEmailModeRequiresEmailTemplateAndIntegration() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"r","config":{"findingId":"{{trigger.entityId}}","mode":"email"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("emailTemplateId"));
    }

    @Test
    void reportFindingEmailModeWithTemplateAndIntegrationIsFine() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"r","config":{"findingId":"{{trigger.entityId}}","mode":"email","emailTemplateId":3,"integrationId":5}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void reportFindingRequiresFindingId() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"r","config":{"mode":"document","reportTemplateId":7}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"n1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("findingId"));
    }

    @Test
    void conditionOnProjectScopedEntityAllowedAtOrganizationScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.ORGANIZATION, json));
    }

    @Test
    void conditionOnProjectScopedEntityRejectedAtPlatformScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PLATFORM, json));
        assertTrue(ex.getMessage().contains("platform-scoped workflows"));
    }

    @Test
    void rejectsGraphWithNoTrigger() {
        String json = graph("""
            "nodes":[{"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}],
            "edges":[]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("trigger"));
    }

    @Test
    void rejectsDanglingEdge() {
        String json = graph("""
            "nodes":[{"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}}],
            "edges":[{"id":"e1","source":"t1","target":"ghost"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("unknown target"));
    }

    @Test
    void rejectsCycle() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}},
              {"id":"n2","type":"ACTION_NOTIFICATION","data":{"label":"b","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"n1","target":"n2"},
              {"id":"e3","source":"n2","target":"n1"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void conditionNodeRequiresBothBranches() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("true") && ex.getMessage().contains("false"));
    }

    @Test
    void conditionAqlReferencingUnknownFieldIsRejected() {
        // Deliberately NOT wrapped in WorkflowValidationException — the underlying
        // AqlFieldNotFoundException already carries a good message and its own 400 mapping in
        // GlobalExceptionHandler, per WorkflowGraphValidator's own stated design.
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"notAField == 1"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertThrows(AqlFieldNotFoundException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void conditionAqlReferencingNonInMemoryFieldIsRejected() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"kevListed == true"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("cannot be evaluated"));
    }

    @Test
    void conditionEntityMatchAqlWithTemplatePlaceholderSkipsParseCheck() {
        // "{{...}}" isn't valid AQL syntax by itself — it's resolved against the run context first
        // (MessagingTemplate.render) — so parse-checking the raw text at save time would always
        // fail. Deferred to run time instead, same as ACTION_AGENT_TASK's targetsFrom.aql.
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == {{variables.p}}"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void countCompareQueryAqlWithTemplatePlaceholderSkipsParseCheck() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"GT",
                "left":{"kind":"QUERY","entityType":"detection","aql":"priority == {{variables.p}}"},
                "right":{"kind":"LITERAL","value":0}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableSourceAqlWithTemplatePlaceholderSkipsParseCheck() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == {{variables.p}}"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void endNodeValidResultAccepted() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"end1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void endNodeRejectsUnknownResult() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"maybe"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"end1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("result"));
    }

    @Test
    void endNodeRejectsOutgoingEdges() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"end1"},
              {"id":"e2","source":"end1","target":"n1"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("terminal"));
    }

    @Test
    void agentTaskRejectedOutsideProjectScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targets":["1.2.3.4"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.ORGANIZATION, json));
        assertTrue(ex.getMessage().contains("project-scoped"));
    }

    @Test
    void agentTaskAllowedAtProjectScopeWithValidArgs() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targets":["1.2.3.4"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    /** No pool member (empty list, Mockito's default for an unstubbed {@code List}-returning
     *  method) reports 'nmap' — save must still succeed (this is advisory, never blocking), but
     *  with exactly one warning naming the node and the tool. */
    @Test
    void agentTaskWarnsWithoutBlockingWhenNoPoolMemberReportsTheTool() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targets":["1.2.3.4"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        WorkflowValidationResult result = validator.validate(WorkflowScope.PROJECT, json);
        assertEquals(1, result.warnings().size());
        assertEquals("a1", result.warnings().get(0).nodeId());
        assertTrue(result.warnings().get(0).message().contains("test-tool"));
    }

    @Test
    void agentTaskHasNoWarningWhenAPoolMemberReportsTheTool() {
        var member = mock(com.martecyber.ares.agents.pools.AgentPoolMember.class);
        when(member.getAgentId()).thenReturn(9L);
        when(agentPoolMemberRepo.findByPoolId(1L)).thenReturn(List.of(member));
        var agent = mock(com.martecyber.ares.agents.Agent.class);
        when(agent.getCapabilities()).thenReturn("[{\"tool\":\"test-tool\",\"path\":\"/usr/bin/test-tool\"}]");
        when(agentRepo.findAllById(List.of(9L))).thenReturn(List.of(agent));

        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targets":["1.2.3.4"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        WorkflowValidationResult result = validator.validate(WorkflowScope.PROJECT, json);
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void agentTaskWithInvalidArgsIsRejected() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskWithAssetAqlTargetsFromIsValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"asset_aql","aql":"type == host","assetTypes":["host"]}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskWithWorkflowVariableTargetsFromIsValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"myAssets","variableType":"asset","sources":[{"entityType":"asset","aql":"type == host"}]}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"myAssets"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskForAMaxTargetsOneToolRejectsAMismatchedBatchSize() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"single-target-tool","batchSize":5,
                "argsTemplate":{"targets":["https://wp.example.com"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("single-target-tool"));
        assertTrue(ex.getMessage().contains("batchSize"));
    }

    @Test
    void agentTaskForAMaxTargetsOneToolRejectsAMissingBatchSize() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"single-target-tool",
                "argsTemplate":{"targets":["https://wp.example.com"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskForAMaxTargetsOneToolAllowedWithMatchingBatchSize() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"single-target-tool","batchSize":1,
                "argsTemplate":{"targets":["https://wp.example.com"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    /** requireComplete=false (a draft save) skips the batchSize check entirely — same
     *  gating as poolId/integrationId elsewhere in this class, since an operator mid-way
     *  through configuring the node hasn't necessarily reached batchSize yet. */
    @Test
    void agentTaskForAMaxTargetsOneToolSkipsTheBatchSizeCheckOnADraftSave() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"tool":"single-target-tool",
                "argsTemplate":{"targets":["https://wp.example.com"]}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json, false));
    }

    @Test
    void agentTaskWithWorkflowVariableRejectsUnknownVariableReference() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"ghost"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void agentTaskWithWorkflowVariableRejectsNonAssetVariable() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"myDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"myDets"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("not 'asset'"));
    }

    @Test
    void agentTaskWithWorkflowVariableRejectsMissingVariableName() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"workflow_variable"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("variableName"));
    }

    @Test
    void agentTaskWithAssetAqlMissingAqlIsRejected() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"asset_aql"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("targetsFrom.aql"));
    }

    @Test
    void agentTaskWithAssetAqlSyntaxErrorIsRejected() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"asset_aql","aql":"type ==="}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskWithTemplatedAssetAqlSkipsParseCheck() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"asset_aql","aql":"identifier ~= {{variables.hostPrefix}}"}}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskWithTemplatedArgsSkipsStrictValidation() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targets":"{{trigger.entity.identifier}}"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void agentTaskMissingPoolIdRejectedWhenRequiringComplete() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"tool":"test-tool"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json, true));
        assertTrue(ex.getMessage().contains("poolId"));
    }

    @Test
    void agentTaskMissingPoolIdAllowedWhenNotRequiringComplete() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"tool":"test-tool"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json, false));
    }

    @Test
    void agentTaskMissingToolStillRejectedEvenWhenNotRequiringComplete() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"a1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json, false));
        assertTrue(ex.getMessage().contains("tool"));
    }

    @Test
    void assignVariableEntityTypeSingleSourceValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableScalarTypeWithFieldProjectionValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"cveNames","variableType":"string","sources":[{"entityType":"cve","aql":"name == foo","field":"name"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableTwoSourcesRequireCombineMode() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"merge","config":{"variableName":"merged","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"},{"entityType":"detection","aql":"priority == P1"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("combineMode"));
    }

    @Test
    void assignVariableTwoSourcesWithCombineModeValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"merge","config":{"variableName":"merged","variableType":"detection","combineMode":"union","sources":[{"entityType":"detection","aql":"priority == P0"},{"entityType":"detection","aql":"priority == P1"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableRejectsInvalidVariableName() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"1bad","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("variableName"));
    }

    @Test
    void assignVariableRejectsMismatchedEntitySource() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x","variableType":"detection","sources":[{"entityType":"cve","aql":"name == foo"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("variableType"));
    }

    @Test
    void assignVariableRejectsScalarTypeSourceWithoutField() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x","variableType":"string","sources":[{"entityType":"cve","aql":"name == foo"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("field"));
    }

    @Test
    void assignVariableQueryRejectedAtPlatformScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PLATFORM, json));
        assertTrue(ex.getMessage().contains("platform-scoped workflows"));
    }

    @Test
    void assignVariableRejectsMissingVariableType() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("variableType"));
    }

    @Test
    void manageTagsValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets","addTagIds":[1,2]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"m1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void manageTagsRejectsUnknownVariableReference() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"ghost","addTagIds":[1]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"m1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void manageTagsRejectsNonTaggableVariableType() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"cveNames","variableType":"string","sources":[{"entityType":"cve","aql":"name == foo","field":"name"}]}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"cveNames","addTagIds":[1]}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"m1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("taggable"));
    }

    @Test
    void manageTagsRejectsEmptyAddAndRemove() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"m1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("addTagIds"));
    }

    @Test
    void updateDetectionStatusValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"noisyDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"u1","type":"ACTION_UPDATE_DETECTION_STATUS","data":{"label":"dismiss","config":{"variableName":"noisyDets","status":"ignored"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"u1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void updateDetectionStatusRejectsUnknownVariableReference() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"u1","type":"ACTION_UPDATE_DETECTION_STATUS","data":{"label":"dismiss","config":{"variableName":"ghost","status":"ignored"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"u1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void updateDetectionStatusRejectsNonDetectionVariableType() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"assets","variableType":"asset","sources":[{"entityType":"asset","aql":"type == host"}]}}},
              {"id":"u1","type":"ACTION_UPDATE_DETECTION_STATUS","data":{"label":"dismiss","config":{"variableName":"assets","status":"ignored"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"u1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("'detection'"));
    }

    @Test
    void updateDetectionStatusRejectsMissingStatus() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"noisyDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"u1","type":"ACTION_UPDATE_DETECTION_STATUS","data":{"label":"dismiss","config":{"variableName":"noisyDets"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"},{"id":"e2","source":"v1","target":"u1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("status"));
    }

    @Test
    void joinThresholdValidAtLeastWithinIncomingCount() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success","joinMode":"AT_LEAST","joinCount":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"end1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"end1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void joinThresholdRejectsCountExceedingIncomingEdges() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success","joinMode":"AT_LEAST","joinCount":3}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"end1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"end1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("joinCount"));
    }

    @Test
    void joinThresholdRejectsUnknownMode() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success","joinMode":"WEIRD"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"end1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("joinMode"));
    }

    @Test
    void loopValidWithBodyFeedingBackAndDoneEdge() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets","addTagIds":[1]}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"m1","sourceHandle":"loop_body"},
              {"id":"e4","source":"m1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void loopValidWithNestedLoopInBody() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"outer","config":{"variableName":"p0Dets"}}},
              {"id":"v2","type":"ASSIGN_VARIABLE","data":{"label":"pull2","config":{"variableName":"p1Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P1"}]}}},
              {"id":"l2","type":"LOOP","data":{"label":"inner","config":{"variableName":"p1Dets"}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p1Dets","addTagIds":[1]}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"v2","sourceHandle":"loop_body"},
              {"id":"e4","source":"v2","target":"l2"},
              {"id":"e5","source":"l2","target":"m1","sourceHandle":"loop_body"},
              {"id":"e6","source":"m1","target":"l2"},
              {"id":"e7","source":"l2","target":"l1","sourceHandle":"loop_done"},
              {"id":"e8","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void loopRequiresBothLoopBodyAndLoopDoneEdges() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets","addTagIds":[1]}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"m1","sourceHandle":"loop_body"},
              {"id":"e4","source":"m1","target":"l1"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("loop_body") && ex.getMessage().contains("loop_done"));
    }

    @Test
    void loopRejectsBodyThatNeverFeedsBack() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets","addTagIds":[1]}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"m1","sourceHandle":"loop_body"},
              {"id":"e4","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("feeds back"));
    }

    @Test
    void loopFeedbackEdgeIsNotRejectedAsACycle() {
        // Same shape as loopValidWithBodyFeedingBackAndDoneEdge — named separately to make explicit
        // that this is exercising assertAcyclic's LOOP exception, not just "the graph is valid".
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"m1","type":"ACTION_NOTIFICATION","data":{"label":"n","config":{"integrationId":1}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"m1","sourceHandle":"loop_body"},
              {"id":"e4","source":"m1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void loopRejectsUnknownVariableReference() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"ghost"}}},
              {"id":"m1","type":"ACTION_NOTIFICATION","data":{"label":"n","config":{"integrationId":1}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"l1"},
              {"id":"e2","source":"l1","target":"m1","sourceHandle":"loop_body"},
              {"id":"e3","source":"m1","target":"l1"},
              {"id":"e4","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void countCompareWithTwoQueryOperandsValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"GT",
                "left":{"kind":"QUERY","entityType":"detection","aql":"priority == P0"},
                "right":{"kind":"QUERY","entityType":"detection","aql":"priority == P1"}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void countCompareWithLiteralOperandValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"GTE",
                "left":{"kind":"QUERY","entityType":"detection","aql":"priority == P0"},
                "right":{"kind":"LITERAL","value":5}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void countCompareWithVariableOperandReferencingRealVariableValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"EQ",
                "left":{"kind":"VARIABLE","variableName":"p0Dets"},
                "right":{"kind":"LITERAL","value":0}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e0","source":"t1","target":"v1"},
              {"id":"e1","source":"v1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void countCompareRejectsUnknownVariableReference() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"EQ",
                "left":{"kind":"VARIABLE","variableName":"ghost"},
                "right":{"kind":"LITERAL","value":0}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    void countCompareRejectsUnknownOperator() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"BOGUS",
                "left":{"kind":"LITERAL","value":1},
                "right":{"kind":"LITERAL","value":0}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("operator"));
    }

    @Test
    void countCompareQueryOperandRejectedAtPlatformScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"GT",
                "left":{"kind":"QUERY","entityType":"detection","aql":"priority == P0"},
                "right":{"kind":"LITERAL","value":0}}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":1}}}
            ],
            "edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"n1","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"n1","sourceHandle":"false"}
            ]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PLATFORM, json));
        assertTrue(ex.getMessage().contains("platform-scoped workflows"));
    }

    @Test
    void assignVariableLimitOrderWithAllowedFieldValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":10,"strategy":"ORDER","field":"lastSeen","direction":"DESC"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableLimitRandomValid() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":10,"strategy":"RANDOM"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void assignVariableLimitRejectsFieldNotInAllowedVocabulary() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":10,"strategy":"ORDER","field":"kevListed","direction":"DESC"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("limit.field"));
    }

    @Test
    void assignVariableLimitRejectsFieldOnRandomStrategy() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":10,"strategy":"RANDOM","field":"title"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("limit.field"));
    }

    @Test
    void assignVariableLimitRejectsNonPositiveCount() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":0,"strategy":"RANDOM"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("limit.count"));
    }

    @Test
    void assignVariableLimitRejectsFieldOnScalarVariable() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"cveNames","variableType":"string",
                "sources":[{"entityType":"cve","aql":"name == foo","field":"name"}],
                "limit":{"count":10,"strategy":"ORDER","field":"name","direction":"ASC"}}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"v1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("scalar variable"));
    }

    @Test
    void integrationCallNodeValidAtProjectScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","integrationId":9,"action":"FIXTURE_ACTION"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void integrationCallNodeRejectedOutsideProjectScope() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","integrationId":9,"action":"FIXTURE_ACTION"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.ORGANIZATION, json));
        assertTrue(ex.getMessage().contains("isn't usable from a organization-scoped workflow"));
    }

    @Test
    void integrationCallNodeRejectsUnknownAction() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","integrationId":9,"action":"DO_MAGIC"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("unknown action"));
    }

    @Test
    void integrationCallNodeRejectsUnknownIntegrationType() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"nope","integrationId":9,"action":"FIXTURE_ACTION"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        // An unregistered type fails on the "no handler at all" check (see
        // IntegrationActionRegistry#missingHandlerMessage) before ever reaching the
        // action-name check below, which only applies to a type that IS registered.
        assertTrue(ex.getMessage().contains("nope"));
        assertTrue(ex.getMessage().contains("no registered handler"));
    }

    @Test
    void integrationCallNodeRequiresAllFields() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("integrationType"));
    }

    @Test
    void integrationCallMissingIntegrationIdAllowedWhenNotRequiringComplete() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","action":"FIXTURE_ACTION"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json, false));
    }

    @Test
    void integrationCallMissingActionStillRejectedEvenWhenNotRequiringComplete() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json, false));
        assertTrue(ex.getMessage().contains("integrationType"));
    }

    @Test
    void callWorkflowNodeValidWithTopic() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"c1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void callWorkflowNodeRequiresTopic() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"c1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("topic"));
    }

    @Test
    void triggerCallTopicNodeValidWithTopic() {
        String json = graph("""
            "nodes":[
              {"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}
            ],
            "edges":[]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void triggerCallTopicNodeRequiresTopic() {
        String json = graph("""
            "nodes":[
              {"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{}}}
            ],
            "edges":[]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("topic"));
    }

    @Test
    void webhookCallNodeValidWithUrl() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{"url":"https://example.com/hook"}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"w1"}]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void webhookCallNodeRequiresUrl() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"w1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("'url'"));
    }

    @Test
    void triggerEventValidWithAKnownEventCode() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_EVENT","data":{"label":"on detection","config":{"eventCode":"detection.created"}}}
            ],
            "edges":[]""");
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void triggerEventRequiresAnEventCode() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_EVENT","data":{"label":"on detection","config":{}}}
            ],
            "edges":[]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("eventCode"));
    }

    @Test
    void triggerEventRejectsAnUnknownEventCode() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_EVENT","data":{"label":"on detection","config":{"eventCode":"detection.renamed"}}}
            ],
            "edges":[]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("eventCode"));
    }

    @Test
    void triggerEventEventCodeIsGatedByWorkflowScope() {
        // PLATFORM: organization/finding_template/cve codes only — detection/finding/asset would
        // be far too noisy platform-wide (every client's every detection). ORGANIZATION: project
        // plus detection/finding/asset (unchanged). PROJECT: detection/finding/asset only.
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PLATFORM,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"organization.created\"}}}],\"edges\":[]")));
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PLATFORM,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"cve.kev_added\"}}}],\"edges\":[]")));
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PLATFORM,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"cve.poc_added\"}}}],\"edges\":[]")));
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PLATFORM,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"finding_template.updated\"}}}],\"edges\":[]")));
        var platformRejectsDetection = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PLATFORM,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"detection.created\"}}}],\"edges\":[]")));
        assertTrue(platformRejectsDetection.getMessage().contains("eventCode"));

        assertDoesNotThrow(() -> validator.validate(WorkflowScope.ORGANIZATION,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"project.created\"}}}],\"edges\":[]")));
        assertDoesNotThrow(() -> validator.validate(WorkflowScope.ORGANIZATION,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"asset.updated\"}}}],\"edges\":[]")));
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.ORGANIZATION,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"organization.created\"}}}],\"edges\":[]")));

        assertDoesNotThrow(() -> validator.validate(WorkflowScope.PROJECT,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"finding.deleted\"}}}],\"edges\":[]")));
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT,
            graph("\"nodes\":[{\"id\":\"t1\",\"type\":\"TRIGGER_EVENT\",\"data\":{\"label\":\"x\",\"config\":{\"eventCode\":\"project.created\"}}}],\"edges\":[]")));
    }

    @Test
    void unsupportedNodeTypeIsRejected() {
        // Every WorkflowNodeType constant is now wired to an executor (Phases A-F all shipped) —
        // a genuinely bogus type string is the only way left to exercise this rejection path.
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_NOT_A_REAL_TYPE","data":{"label":"call","config":{}}}
            ],
            "edges":[{"id":"e1","source":"t1","target":"s1"}]""");
        var ex = assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
        assertTrue(ex.getMessage().contains("not supported yet"));
    }

    @Test
    void duplicateNodeIdIsRejected() {
        String json = graph("""
            "nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"a","config":{}}},
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"b","config":{}}}
            ],
            "edges":[]""");
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, json));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(WorkflowValidationException.class, () -> validator.validate(WorkflowScope.PROJECT, "{not json"));
    }
}
