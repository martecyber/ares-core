package com.martecyber.ares.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.CreateTask;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos.TaskDto;
import com.martecyber.ares.aql.AqlRegistryLookup;
import com.martecyber.ares.aql.parser.AqlOperator;
import com.martecyber.ares.aql.registry.*;
import com.martecyber.ares.assets.Asset;
import com.martecyber.ares.assets.AssetRepository;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.detections.Detection;
import com.martecyber.ares.detections.DetectionRepository;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.detections.dto.DetectionDto;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.findings.dto.FindingDto;
import com.martecyber.ares.integrations.notifications.MessagingIntegration;
import com.martecyber.ares.integrations.notifications.MessagingIntegrationRepository;
import com.martecyber.ares.integrations.notifications.MessagingService;
import com.martecyber.ares.integrations.notifications.NotificationMessage;
import com.martecyber.ares.kb.emailtemplates.EmailTemplate;
import com.martecyber.ares.workflows.integrations.IntegrationActionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;

/**
 * Exercises {@link WorkflowRunService}'s join/branch/skip-propagation algorithm against real
 * Postgres-backed repositories (mirrors the AQL compiler IT suites' structure/rationale) — the
 * one piece of this engine with genuine algorithmic risk. {@code MessagingService} and {@code
 * AgentTaskService} are plain Mockito mocks, not Spring beans: their own correctness isn't this
 * test's concern (each has its own established test coverage), and mocking them keeps this test
 * free of real network calls / agent-task preconditions (pools, target resolution) while still
 * exercising real JPA persistence for the run/step-run state machine itself.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
class WorkflowRunServiceIT {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        int port = Integer.getInteger("AQL_IT_PG_PORT", 15432);
        String jdbcUrl = "jdbc:postgresql://localhost:" + port + "/ares";
        registry.add("spring.datasource.url", () -> jdbcUrl + "?currentSchema=ares");
        registry.add("spring.datasource.username", () -> "ares");
        registry.add("spring.datasource.password", () -> "ares");
        registry.add("spring.flyway.url", () -> jdbcUrl);
        registry.add("spring.flyway.user", () -> "ares");
        registry.add("spring.flyway.password", () -> "ares");
    }

    private static final Set<AqlOperator> NUMBER_OPS = EnumSet.of(
        AqlOperator.EQ, AqlOperator.NEQ, AqlOperator.GT, AqlOperator.GTE, AqlOperator.LT, AqlOperator.LTE);

    private static final class FixtureDetectionRegistry implements EntityAqlRegistry<Detection> {
        private final Map<String, AqlField<Detection>> fields = new LinkedHashMap<>();
        FixtureDetectionRegistry() {
            fields.put("priority", new ColumnAqlField<>("priority", AqlFieldType.PRIORITY, AqlFieldKind.PHYSICAL_COLUMN,
                NUMBER_OPS, r -> r.get("priority"), AqlField.PRIORITY_LABELS, (java.util.function.Function<Detection, Object>) Detection::getPriority));
        }
        @Override public String entityName() { return "detection"; }
        @Override public Optional<AqlField<Detection>> field(String name) { return Optional.ofNullable(fields.get(name)); }
        @Override public List<AqlField<Detection>> defaultSearchFields() { return List.of(); }
        @Override public List<AqlField<Detection>> allFields() { return List.copyOf(fields.values()); }
    }

    private static final class FixtureActionHandler implements com.martecyber.ares.workflows.integrations.IntegrationActionHandler {
        @Override public String integrationType() { return "fixture-type"; }
        @Override public String integrationTypeLabel() { return "Fixture"; }
        @Override public java.util.Set<String> supportedScopes() { return java.util.Set.of("project"); }
        @Override public List<com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor> describeActions() {
            return List.of(new com.martecyber.ares.workflows.integrations.IntegrationActionDescriptor("FIXTURE_ACTION", "Fixture action"));
        }
        @Override public List<com.martecyber.ares.workflows.integrations.IntegrationInstanceDescriptor> listInstances(String scopeKind, Long scopeId) { return List.of(); }
        @Override public Long start(String actionCode, Long integrationInstanceId, String scopeKind, Long scopeId, Map<String, Object> params) { return 999L; }
        @Override public com.martecyber.ares.workflows.integrations.IntegrationActionResult checkStatus(Long refId) {
            return new com.martecyber.ares.workflows.integrations.IntegrationActionResult(
                com.martecyber.ares.workflows.integrations.IntegrationActionResult.COMPLETED, "{}", null);
        }
    }

    @Autowired private WorkflowRepository workflowRepo;
    @Autowired private WorkflowRunRepository runRepo;
    @Autowired private WorkflowStepRunRepository stepRunRepo;
    @Autowired private WorkflowTriggerRepository triggerRepo;
    @Autowired private MessagingIntegrationRepository messagingIntegrationRepo;
    @Autowired private com.martecyber.ares.kb.emailtemplates.EmailTemplateRepository emailTemplateRepo;
    @Autowired private DetectionRepository detectionRepo;
    @Autowired private AssetRepository assetRepo;
    @Autowired private com.martecyber.ares.projects.ProjectRepository projectRepo;
    @Autowired private com.martecyber.ares.organizations.OrganizationRepository organizationRepo;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MessagingService messagingService;
    private DetectionService detectionService;
    private FindingService findingService;
    private AssetService assetService;
    private WorkflowService workflowService;
    private AgentTaskService agentTaskService;
    private com.martecyber.ares.reporting.ReportGenerationService reportGenerationService;
    private com.martecyber.ares.reporting.FindingEmailReportService findingEmailReportService;
    private WorkflowRunService service;
    private Long integrationId;
    private Long emailIntegrationId;
    private Long detectionId;
    private Long projectId;
    private Long organizationId;

    /** Synthetic fixture spec — NOT modeled on any real plugin's tool. AgentToolSpecRegistry is
     *  only populated at runtime by an installed plugin, so tests exercising the ACTION_AGENT_TASK
     *  node need to register something themselves; a fake 'test-tool' keeps this test suite from
     *  encoding any real plugin's actual field/flag shape into ares-core — mirrors
     *  WorkflowGraphValidatorTest's identical fixture. */
    private static com.martecyber.ares.agents.tasks.AgentToolSpecRegistry testToolSpecRegistry() {
        var registry = new com.martecyber.ares.agents.tasks.AgentToolSpecRegistry();
        registry.register(new com.martecyber.ares.agents.tasks.AgentToolSpec(
            "test-tool", "Test Tool", "test-tool", "default", Set.of(), Set.of("ip"),
            null, "positional", null, null, null, false, null, List.of()), null);
        return registry;
    }

    @BeforeEach
    void setUp() {
        messagingService = mock(MessagingService.class);
        agentTaskService = mock(AgentTaskService.class);
        detectionService = mock(DetectionService.class);
        findingService = mock(FindingService.class);
        assetService = mock(AssetService.class);
        reportGenerationService = mock(com.martecyber.ares.reporting.ReportGenerationService.class);
        findingEmailReportService = mock(com.martecyber.ares.reporting.FindingEmailReportService.class);
        AqlRegistryLookup aqlRegistries = new AqlRegistryLookup(List.of(new FixtureDetectionRegistry()));
        WorkflowEntityLookup entityLookup = new WorkflowEntityLookup(List.of(
            new com.martecyber.ares.detections.DetectionWorkflowEntityLoader(detectionRepo)));
        workflowService = mock(WorkflowService.class);
        when(workflowService.getOutboundWebhookSecretPlaintext(anyLong(), anyString())).thenReturn(java.util.Optional.empty());
        service = new WorkflowRunService(workflowRepo, runRepo, stepRunRepo, aqlRegistries, entityLookup,
            messagingService, messagingIntegrationRepo, agentTaskService, assetService, findingService, detectionService,
            projectRepo, triggerRepo, workflowService, new com.martecyber.ares.webhooks.WebhookSignatureService(),
            new IntegrationActionRegistry(List.of(new FixtureActionHandler()), mock(com.martecyber.ares.plugins.PluginRepository.class)),
            mock(com.martecyber.ares.aql.AqlQueryableEntityRegistry.class),
            mock(com.martecyber.ares.kb.exploits.ExploitService.class),
            mock(com.martecyber.ares.findings.templates.FindingTemplateService.class),
            testToolSpecRegistry(), reportGenerationService, findingEmailReportService);

        MessagingIntegration integration = new MessagingIntegration();
        integration.setName("test-integration-" + System.nanoTime());
        integration.setKind("webhook");
        integration.setEnabled(true);
        integration.setConfigCiphertext(new byte[]{1, 2, 3});
        integration.setConfigIv(new byte[]{4, 5, 6});
        integrationId = messagingIntegrationRepo.save(integration).getId();

        MessagingIntegration emailIntegration = new MessagingIntegration();
        emailIntegration.setName("test-email-integration-" + System.nanoTime());
        emailIntegration.setKind("email");
        emailIntegration.setEnabled(true);
        emailIntegration.setConfigCiphertext(new byte[]{1, 2, 3});
        emailIntegration.setConfigIv(new byte[]{4, 5, 6});
        emailIntegrationId = messagingIntegrationRepo.save(emailIntegration).getId();

        var now0 = java.time.OffsetDateTime.now();
        var org = new com.martecyber.ares.organizations.Organization();
        org.setName("WF Test Org " + System.nanoTime());
        org.setSlug("wf-test-org-" + System.nanoTime());
        org.setCreatedAt(now0);
        org.setUpdatedAt(now0);
        organizationId = organizationRepo.save(org).getId();
        var project = new com.martecyber.ares.projects.Project();
        project.setOrganizationId(organizationId);
        project.setName("WF Test Project");
        project.setCreatedAt(now0);
        project.setUpdatedAt(now0);
        projectId = projectRepo.save(project).getId();

        Detection d = new Detection();
        d.setProjectId(projectId);
        d.setSeverity("critical");
        d.setPriority((short) 0);
        d.setStatus("new");
        d.setStatusId(1L);
        d.setTitle("test detection");
        d.setCreatedAt(java.time.OffsetDateTime.now());
        d.setUpdatedAt(java.time.OffsetDateTime.now());
        detectionId = detectionRepo.save(d).getId();
    }

    private Workflow saveWorkflow(String graphJson) {
        return saveWorkflow(graphJson, WorkflowScope.PLATFORM, WorkflowScope.PLATFORM_SCOPE_ID);
    }

    private Workflow saveWorkflow(String graphJson, String scopeKind, Long scopeId) {
        Workflow wf = new Workflow();
        wf.setScopeKind(scopeKind);
        wf.setScopeId(scopeId);
        wf.setName("test workflow");
        wf.setStatus("active");
        wf.setGraphDefinition(graphJson);
        var now = java.time.OffsetDateTime.now();
        wf.setCreatedAt(now);
        wf.setUpdatedAt(now);
        return workflowRepo.save(wf);
    }

    /** A second, unrelated org+project pair — for exercising the "horizontal" (sideways) rejection
     *  cases ACTION_CALL_WORKFLOW's vertical-scope rule must still reject even though platform is
     *  now allowed to reach anything below it. */
    private record OrgAndProject(Long orgId, Long projectId) {}

    private OrgAndProject createOrgAndProject() {
        var now = java.time.OffsetDateTime.now();
        var org = new com.martecyber.ares.organizations.Organization();
        org.setName("WF Test Org 2 " + System.nanoTime());
        org.setSlug("wf-test-org-2-" + System.nanoTime());
        org.setCreatedAt(now);
        org.setUpdatedAt(now);
        Long orgId = organizationRepo.save(org).getId();
        var project = new com.martecyber.ares.projects.Project();
        project.setOrganizationId(orgId);
        project.setName("WF Test Project 2");
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        Long projId = projectRepo.save(project).getId();
        return new OrgAndProject(orgId, projId);
    }

    private void saveCallTopicTrigger(Long workflowId, String nodeId, String topic) {
        WorkflowTrigger t = new WorkflowTrigger();
        t.setWorkflowId(workflowId);
        t.setNodeId(nodeId);
        t.setTriggerType(WorkflowTriggerType.CALL_TOPIC);
        t.setEnabled(true);
        t.setConfig("{\"topic\":\"" + topic + "\"}");
        t.setCreatedAt(java.time.OffsetDateTime.now());
        triggerRepo.save(t);
    }

    private DetectionDto detectionDto(long id, String title) {
        return new DetectionDto(id, projectId, null, null, "10.0.0." + id, "critical", "new", title,
            null, null, null, null, 1, OffsetDateTime.now(), OffsetDateTime.now(), null, List.of(), List.of(), List.of(), List.of());
    }

    private Asset saveAsset(String type, String identifier) {
        Asset a = new Asset();
        a.setCode("A-" + System.nanoTime());
        a.setOrganizationId(organizationId);
        a.setType(type);
        a.setIdentifier(identifier);
        a.setCreatedAt(OffsetDateTime.now());
        return assetRepo.save(a);
    }

    /** Minimal TaskDto for mocking agentTaskService.createAll's return — only {@code id} matters
     *  to callers (executeAgentTask reads it for the step's ref/output), everything else is
     *  filler. */
    private TaskDto taskDto(long id) {
        return new TaskDto(id, null, projectId, 1L, null, null, "test-tool", "default", null, null, null,
            "pending", 0, null, OffsetDateTime.now(), null, null, null, null,
            null, null, null, null, null, null, null);
    }

    private Map<String, Object> triggerContext() {
        return Map.of("entityId", detectionId, "entityType", "detection");
    }

    @Test
    void simpleTriggerToNotificationCompletes() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(messagingService, times(1)).send(any(), any(NotificationMessage.class), eq(true));
        List<WorkflowStepRun> steps = stepRunRepo.findByWorkflowRunId(run.getId());
        assertEquals(2, steps.size());
        assertTrue(steps.stream().allMatch(s -> WorkflowStepStatus.COMPLETED.equals(s.getStatus())));
    }

    @Test
    void notificationBodyIsFlaggedMarkdownWithEscapedSubstitutedValues() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,
                "titleTemplate":"Alert","bodyTemplate":"**Host** {{trigger.hostname}} is down"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of("hostname", "my_host_name"), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService).send(any(), captor.capture(), eq(true));
        NotificationMessage sent = captor.getValue();
        assertTrue(sent.markdown());
        // The template's own **bold** markers survive; the substituted value's underscores are
        // escaped so the CommonMark parser (downstream, per-sender) doesn't read them as emphasis.
        assertEquals("**Host** my\\_host\\_name is down", sent.body());
    }

    @Test
    void emailIntegrationRendersAndValidatesRecipients() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,
                "to":"{{trigger.owner}}, second@example.com","cc":"cc@example.com"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(emailIntegrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of("owner", "first@example.com"), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService).send(any(), captor.capture(), eq(true));
        NotificationMessage sent = captor.getValue();
        assertEquals(List.of("first@example.com", "second@example.com"), sent.to());
        assertEquals(List.of("cc@example.com"), sent.cc());
        assertTrue(sent.bcc().isEmpty());
    }

    @Test
    void emailIntegrationRejectsAnInvalidRecipientAddress() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,
                "to":"not-an-address"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(emailIntegrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        verify(messagingService, never()).send(any(), any(), anyBoolean());
    }

    @Test
    void reportFindingDocumentModeGeneratesTheDocumentAndExposesTheReportId() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"report","config":{
                "findingId":"42","mode":"document","reportTemplateId":7}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""");

        com.martecyber.ares.findings.dto.FindingDto finding = mock(com.martecyber.ares.findings.dto.FindingDto.class);
        when(finding.projectId()).thenReturn(projectId);
        when(findingService.get(42L)).thenReturn(finding);

        com.martecyber.ares.reporting.dto.ReportDto createdReport = mock(com.martecyber.ares.reporting.dto.ReportDto.class);
        when(createdReport.id()).thenReturn(555L);
        when(reportGenerationService.create(any())).thenReturn(createdReport);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(com.martecyber.ares.reporting.dto.GenerateReportRequest.class);
        verify(reportGenerationService).create(captor.capture());
        assertEquals(List.of(42L), captor.getValue().findingIds());
        assertEquals(7L, captor.getValue().templateId());
        verify(reportGenerationService).generateDocument(555L, 7L);
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertTrue(byNode.get("n1").getOutput().contains("\"reportId\":555"));
    }

    @Test
    void reportFindingEmailModeSendsThroughFindingEmailReportService() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_REPORT_FINDING","data":{"label":"report","config":{
                "findingId":"{{trigger.entityId}}","mode":"email","emailTemplateId":3,"integrationId":%d,
                "to":"soc@example.com"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(emailIntegrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of("entityId", "42"), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(findingEmailReportService).sendEmailForFinding(42L, 3L, emailIntegrationId, List.of("soc@example.com"), List.of(), List.of());
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertTrue(byNode.get("n1").getOutput().contains("\"sent\":true"));
    }

    @Test
    void conditionTrueBranchTakesTruePathAndSkipsFalsePath() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"entityType":"detection","aql":"priority == P0"}}},
              {"id":"nTrue","type":"ACTION_NOTIFICATION","data":{"label":"onTrue","config":{"integrationId":%d}}},
              {"id":"nFalse","type":"ACTION_NOTIFICATION","data":{"label":"onFalse","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"nTrue","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"nFalse","sourceHandle":"false"}
            ]}""".formatted(integrationId, integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", triggerContext(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(messagingService, times(1)).send(any(), any(), eq(true));
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("nTrue").getStatus());
        assertEquals(WorkflowStepStatus.SKIPPED, byNode.get("nFalse").getStatus());
    }

    @Test
    void countCompareTrueBranchTakenWhenQueryCountExceedsLiteral() throws Exception {
        when(detectionService.countByAql(any(), any(), eq("priority == P0"))).thenReturn(2L);
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"GT",
                "left":{"kind":"QUERY","entityType":"detection","aql":"priority == P0"},
                "right":{"kind":"LITERAL","value":1}}}},
              {"id":"nTrue","type":"ACTION_NOTIFICATION","data":{"label":"onTrue","config":{"integrationId":%d}}},
              {"id":"nFalse","type":"ACTION_NOTIFICATION","data":{"label":"onFalse","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"c1"},
              {"id":"e2","source":"c1","target":"nTrue","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"nFalse","sourceHandle":"false"}
            ]}""".formatted(integrationId, integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("nTrue").getStatus());
        assertEquals(WorkflowStepStatus.SKIPPED, byNode.get("nFalse").getStatus());
        var output = MAPPER.readTree(byNode.get("c1").getOutput());
        assertTrue(output.get("result").asBoolean());
        assertEquals(2, output.get("leftCount").asLong());
        assertEquals(1, output.get("rightCount").asLong());
    }

    @Test
    void countCompareUsesAssignedVariableItemCount() {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"c1","type":"CONDITION","data":{"label":"cond","config":{"mode":"COUNT_COMPARE","operator":"EQ",
                "left":{"kind":"VARIABLE","variableName":"p0Dets"},
                "right":{"kind":"LITERAL","value":2}}}},
              {"id":"nTrue","type":"ACTION_NOTIFICATION","data":{"label":"onTrue","config":{"integrationId":%d}}},
              {"id":"nFalse","type":"ACTION_NOTIFICATION","data":{"label":"onFalse","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e0","source":"t1","target":"v1"},
              {"id":"e1","source":"v1","target":"c1"},
              {"id":"e2","source":"c1","target":"nTrue","sourceHandle":"true"},
              {"id":"e3","source":"c1","target":"nFalse","sourceHandle":"false"}
            ]}""".formatted(integrationId, integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("nTrue").getStatus());
        assertEquals(WorkflowStepStatus.SKIPPED, byNode.get("nFalse").getStatus());
    }

    @Test
    void fanOutRunsBothParallelActions() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"n2","type":"ACTION_NOTIFICATION","data":{"label":"b","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"t1","target":"n2"}
            ]}""".formatted(integrationId, integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(messagingService, times(2)).send(any(), any(), eq(true));
    }

    @Test
    void fanInWaitsForBothParallelPredecessorsBeforeRunning() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"n2","type":"ACTION_NOTIFICATION","data":{"label":"b","config":{"integrationId":%d}}},
              {"id":"n3","type":"ACTION_NOTIFICATION","data":{"label":"join","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"t1","target":"n2"},
              {"id":"e3","source":"n1","target":"n3"},
              {"id":"e4","source":"n2","target":"n3"}
            ]}""".formatted(integrationId, integrationId, integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(messagingService, times(3)).send(any(), any(), eq(true));
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("n3").getStatus());
    }

    @Test
    void unhandledFailureFailsTheRun() {
        doThrow(new RuntimeException("delivery failed")).when(messagingService).send(any(), any(), eq(true));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}}
            ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertNotNull(run.getError());
    }

    @Test
    void endNodeWithSuccessResultCompletesRunNormally() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"end1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertNull(run.getError());
    }

    /**
     * Simulates the production bug this reconciliation query exists for: every step (including
     * END) already reached a terminal status, but the run row's own final status update never
     * landed — e.g. {@code advance()}'s transaction was interrupted right after its node loop
     * settled. {@link WorkflowStepRunRepository#findRunningWorkflowRunIdsWithNoActiveSteps} (used
     * by {@link WorkflowStepPoller}'s sweep) must still find it, and re-invoking {@code advance()}
     * must finalize it correctly — the same generic "no active steps left" rule this exercises
     * elsewhere for graphs with no END node at all, not anything END-specific.
     */
    @Test
    void reconciliationQueryFindsAndAdvanceFixesARunStuckRunningAfterItsStepsAllFinished() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"end1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);
        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());

        // Force the already-finished run back to 'running', as if its own advance() call never
        // committed its final status update even though every step (END included) already did.
        WorkflowRun stuck = runRepo.findById(run.getId()).orElseThrow();
        stuck.setStatus(WorkflowRunStatus.RUNNING);
        stuck.setCompletedAt(null);
        runRepo.save(stuck);

        List<Long> found = stepRunRepo.findRunningWorkflowRunIdsWithNoActiveSteps();
        assertTrue(found.contains(run.getId()));

        service.advance(run.getId());

        WorkflowRun healed = runRepo.findById(run.getId()).orElseThrow();
        assertEquals(WorkflowRunStatus.COMPLETED, healed.getStatus());
        assertNotNull(healed.getCompletedAt());
    }

    @Test
    void endNodeWithFailureResultFailsTheRunWithRenderedMessage() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"failure","message":"no match for {{trigger.entityId}}"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"end1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of("entityId", 42), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertEquals("no match for 42", run.getError());
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("end1").getStatus());
    }

    @Test
    void endNodeFailureOnOneBranchStillLetsSiblingBranchFinishFirst() {
        // Both branches must fully settle before the END-triggered failure flips the run's final
        // status — a failure signaled on one branch must never cut a sibling branch short.
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"end1","type":"END","data":{"label":"fail","config":{"result":"failure"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"t1","target":"end1"}
            ]}""".formatted(integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        verify(messagingService, times(1)).send(any(), any(), eq(true));
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("n1").getStatus());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("end1").getStatus());
    }

    @Test
    void nodeExecutionFailureThatCantPersistItselfStillRecordsTheRunAndTheFailingStep() throws Exception {
        // Reproduces the real bug this fixes: executeNode's OWN (REQUIRES_NEW) transaction gets
        // poisoned by a nested @Transactional call before its own catch block can save the FAILED
        // step — stood in for here by making the self-proxy's executeNode() throw outright, same
        // observable effect ("even executeNode's own attempt to persist its failure blew up too").
        // advance()'s own fallback (recordCrashedStep) must still leave a durable, queryable
        // record of the run and exactly which node broke, in its own separate transaction.
        // A spy (not a bare mock) — start()/advance() route createRun()/executeNode() through
        // `self` unconditionally now (not just executeNode), so every OTHER method self is used
        // for still needs to run for real; only executeNode() itself is overridden.
        WorkflowRunService selfSpy = spy(service);
        doThrow(new RuntimeException("simulated poisoned transaction")).when(selfSpy).executeNode(any(), any(), any());
        java.lang.reflect.Field selfField = WorkflowRunService.class.getDeclaredField("self");
        selfField.setAccessible(true);
        selfField.set(service, selfSpy);
        try {
            Workflow wf = saveWorkflow("""
                {"nodes":[
                  {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
                  {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}}
                ],"edges":[{"id":"e1","source":"t1","target":"n1"}]}""".formatted(integrationId));

            WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

            assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
            assertEquals("simulated poisoned transaction", run.getError());
            Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
            assertEquals(WorkflowStepStatus.FAILED, byNode.get("n1").getStatus());
            assertEquals("simulated poisoned transaction", byNode.get("n1").getError());
        } finally {
            selfField.set(service, null);
        }
    }

    @Test
    void failureWithErrorEdgeRoutesToErrorHandlerInsteadOfFailingRun() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"errHandler","type":"ACTION_NOTIFICATION","data":{"label":"onError","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"n1","target":"errHandler","sourceHandle":"error"}
            ]}""".formatted(integrationId, integrationId));

        // First send() (n1) fails, routing to errHandler; errHandler's own send() succeeds.
        doThrow(new RuntimeException("first failure")).doNothing().when(messagingService).send(any(), any(), eq(true));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.FAILED, byNode.get("n1").getStatus());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("errHandler").getStatus());
        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void downstreamNodeCanReferenceAnEarlierStepsSuccessOutputViaStepsPlaceholder() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"n2","type":"ACTION_NOTIFICATION","data":{"label":"b","config":{"integrationId":%d,
                "titleTemplate":"n1 sent: {{steps.n1.output.sent}}"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"n1","target":"n2"}
            ]}""".formatted(integrationId, integrationId));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService, times(2)).send(any(), captor.capture(), eq(true));
        assertEquals("n1 sent: true", captor.getAllValues().get(1).title());
    }

    @Test
    void errorHandlerNodeCanReferenceTheFailedStepsErrorMessageViaStepsPlaceholder() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"a","config":{"integrationId":%d}}},
              {"id":"errHandler","type":"ACTION_NOTIFICATION","data":{"label":"onError","config":{"integrationId":%d,
                "titleTemplate":"failed: {{steps.n1.error}}"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"n1"},
              {"id":"e2","source":"n1","target":"errHandler","sourceHandle":"error"}
            ]}""".formatted(integrationId, integrationId));

        doThrow(new RuntimeException("boom")).doNothing().when(messagingService).send(any(), any(), eq(true));

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService, times(2)).send(any(), captor.capture(), eq(true));
        assertEquals("failed: boom", captor.getAllValues().get(1).title());
    }

    @Test
    void agentTaskNodeRejectedOutsideProjectScopedWorkflow() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"a1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("project-scoped"));
    }

    @Test
    void agentTaskResolvesTargetsFromWorkflowVariableFilteredByToolAssetTypes() throws Exception {
        Asset ip = saveAsset("ip", "10.0.0.5");
        Asset webEndpoint = saveAsset("web_endpoint", "https://example.com/api"); // test-tool doesn't accept this type — must be filtered out
        when(assetService.listByAql(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(ip, webEndpoint)));
        var taskCaptor = org.mockito.ArgumentCaptor.forClass(CreateTask.class);
        when(agentTaskService.createAll(eq(projectId), taskCaptor.capture(), any())).thenReturn(List.of(taskDto(501L)));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"myAssets","variableType":"asset","sources":[{"entityType":"asset","aql":"type != null"}]}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"myAssets"}}}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"a1"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.RUNNING, run.getStatus());
        assertEquals(WorkflowStepStatus.WAITING, stepsByNode(run.getId()).get("a1").getStatus());

        CreateTask req = taskCaptor.getValue();
        assertFalse(req.args().containsKey("targetsFrom"));
        assertEquals(List.of("10.0.0.5"), req.args().get("targets"));
    }

    @Test
    void agentTaskPopulatesNacProfileAndBypassTimeWindowFromNodeConfig() throws Exception {
        var taskCaptor = org.mockito.ArgumentCaptor.forClass(CreateTask.class);
        when(agentTaskService.createAll(eq(projectId), taskCaptor.capture(), any())).thenReturn(List.of(taskDto(502L)));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "nacProfile":"eu-west-vpn","bypassTimeWindow":true,"argsTemplate":{}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"a1"}]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.RUNNING, run.getStatus());
        CreateTask req = taskCaptor.getValue();
        assertEquals("eu-west-vpn", req.nacProfile());
        assertTrue(req.bypassTimeWindow());
    }

    @Test
    void agentTaskDefaultsNacProfileAndBypassTimeWindowWhenNotSetInNodeConfig() throws Exception {
        var taskCaptor = org.mockito.ArgumentCaptor.forClass(CreateTask.class);
        when(agentTaskService.createAll(eq(projectId), taskCaptor.capture(), any())).thenReturn(List.of(taskDto(503L)));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"a1"}]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.RUNNING, run.getStatus());
        CreateTask req = taskCaptor.getValue();
        assertNull(req.nacProfile());
        assertFalse(req.bypassTimeWindow());
    }

    @Test
    void agentTaskFailsWhenNothingInVariableIsCompatibleWithTheTool() throws Exception {
        Asset webEndpoint = saveAsset("web_endpoint", "https://example.com/api");
        when(assetService.listByAql(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(webEndpoint)));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"myAssets","variableType":"asset","sources":[{"entityType":"asset","aql":"type != null"}]}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"myAssets"}}}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"a1"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("no assets"));
        verify(agentTaskService, never()).createAll(any(), any(), any());
    }

    @Test
    void agentTaskWorkflowVariableRejectsNonAssetVariableAtRuntime() {
        // Bypasses save-time validation on purpose (saveWorkflow() persists the graph directly,
        // not through WorkflowService/WorkflowGraphValidator) — exercises executeAgentTask's own
        // defensive runtime re-check, mirroring WorkflowGraphValidator#validateTargetsFrom's
        // save-time one but for a graph that was never actually validated.
        when(detectionService.listByAql(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "det one"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"myDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool","argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"myDets"}}}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"a1"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("not 'asset'"));
    }

    @Test
    void assignVariableSingleSourceStoresMatchesInContext() throws Exception {
        when(detectionService.listByAql(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "det one"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"n1"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        assertEquals(WorkflowStepStatus.COMPLETED, byNode.get("v1").getStatus());
        var output = MAPPER.readTree(byNode.get("v1").getOutput());
        assertEquals("p0Dets", output.get("variableName").asText());
        assertEquals("detection", output.get("variableType").asText());
        assertEquals(1, output.get("count").asInt());

        var context = MAPPER.readTree(run.getContext());
        var items = context.path("variables").path("p0Dets").path("items");
        assertEquals(1, items.size());
        assertEquals(1L, items.get(0).get("id").asLong());
        assertEquals("det one", items.get(0).get("title").asText());
    }

    @Test
    void assignVariableUnionModeCombinesByIdAcrossInlineSources() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"))));
        when(detectionService.listByAql(any(), any(), eq("priority == P1"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(2L, "b"), detectionDto(3L, "c"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"merge","config":{"variableName":"merged","variableType":"detection","combineMode":"union","sources":[
                {"entityType":"detection","aql":"priority == P0"},
                {"entityType":"detection","aql":"priority == P1"}
              ]}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"n1"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var context = MAPPER.readTree(run.getContext());
        var items = context.path("variables").path("merged").path("items");
        assertEquals(3, items.size());
        var ids = new java.util.HashSet<Long>();
        items.forEach(i -> ids.add(i.get("id").asLong()));
        assertEquals(Set.of(1L, 2L, 3L), ids);
    }

    @Test
    void assignVariableIntersectionModeKeepsOnlyItemsPresentInEverySource() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"))));
        when(detectionService.listByAql(any(), any(), eq("priority == P1"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(2L, "b"), detectionDto(3L, "c"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"merge","config":{"variableName":"merged","variableType":"detection","combineMode":"intersection","sources":[
                {"entityType":"detection","aql":"priority == P0"},
                {"entityType":"detection","aql":"priority == P1"}
              ]}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"n1"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var context = MAPPER.readTree(run.getContext());
        var items = context.path("variables").path("merged").path("items");
        assertEquals(1, items.size());
        assertEquals(2L, items.get(0).get("id").asLong());
    }

    @Test
    void assignVariableLimitOrderTruncatesAndOrdersCombinedList() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "charlie"), detectionDto(2L, "alpha"), detectionDto(3L, "bravo"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"top2","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":2,"strategy":"ORDER","field":"title","direction":"ASC"}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"v1"}]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var context = MAPPER.readTree(run.getContext());
        var items = context.path("variables").path("top2").path("items");
        assertEquals(2, items.size());
        assertEquals("alpha", items.get(0).get("title").asText());
        assertEquals("bravo", items.get(1).get("title").asText());
        assertFalse(items.get(0).has("_sortKey"));
        assertFalse(items.get(1).has("_sortKey"));
    }

    @Test
    void assignVariableLimitRandomTruncatesToCount() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"), detectionDto(3L, "c"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"sample","variableType":"detection",
                "sources":[{"entityType":"detection","aql":"priority == P0"}],
                "limit":{"count":2,"strategy":"RANDOM"}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"v1"}]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var context = MAPPER.readTree(run.getContext());
        var items = context.path("variables").path("sample").path("items");
        assertEquals(2, items.size());
        var ids = new java.util.HashSet<Long>();
        items.forEach(i -> ids.add(i.get("id").asLong()));
        assertTrue(Set.of(1L, 2L, 3L).containsAll(ids));
    }

    @Test
    void manageTagsAssignsAndRemovesTagsOnEachTargetEntity() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"m1","type":"ACTION_MANAGE_TAGS","data":{"label":"tag","config":{"variableName":"p0Dets","addTagIds":[10],"removeTagIds":[20]}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"m1"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(detectionService).assignTag(1L, 10L);
        verify(detectionService).assignTag(2L, 10L);
        verify(detectionService).unassignTag(1L, 20L);
        verify(detectionService).unassignTag(2L, 20L);

        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        var output = MAPPER.readTree(byNode.get("m1").getOutput());
        assertEquals("detection", output.get("variableType").asText());
        assertEquals(2, output.get("targetCount").asInt());
        assertEquals(2, output.get("tagsAdded").asInt());
        assertEquals(2, output.get("tagsRemoved").asInt());
    }

    @Test
    void updateDetectionStatusSetsStatusOnEachTarget() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "a"), detectionDto(2L, "b"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"noisyDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"u1","type":"ACTION_UPDATE_DETECTION_STATUS","data":{"label":"dismiss","config":{"variableName":"noisyDets","status":"ignored","note":"auto-ignored by workflow"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"u1"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(detectionService).updateStatus(1L, "ignored", "auto-ignored by workflow");
        verify(detectionService).updateStatus(2L, "ignored", "auto-ignored by workflow");

        Map<String, WorkflowStepRun> byNode = stepsByNode(run.getId());
        var output = MAPPER.readTree(byNode.get("u1").getOutput());
        assertEquals("ignored", output.get("status").asText());
        assertEquals(2, output.get("targetCount").asInt());
    }

    @Test
    void loopRunsBodyOncePerItemAndTemplatesLoopItemIndexAndCount() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "alpha"), detectionDto(2L, "bravo"))));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,
                "titleTemplate":"{{loop.index}}/{{loop.count}}: {{loop.item.title}}"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"n1","sourceHandle":"loop_body"},
              {"id":"e4","source":"n1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var messageCaptor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService, times(2)).send(any(), messageCaptor.capture(), anyBoolean());
        List<String> titles = messageCaptor.getAllValues().stream().map(NotificationMessage::title).toList();
        assertTrue(titles.contains("0/2: alpha"));
        assertTrue(titles.contains("1/2: bravo"));

        var output = MAPPER.readTree(stepsByNode(run.getId()).get("l1").getOutput());
        assertFalse(output.get("hasNext").asBoolean());
        assertEquals("p0Dets", output.get("variableName").asText());
        assertEquals(2, output.get("itemCount").asInt());
        assertEquals(2, output.get("itemsSucceeded").asInt());
        assertEquals(0, output.get("itemsFailed").asInt());
    }

    @Test
    void loopWithZeroItemsCompletesTrivially() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of()));
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"n1","sourceHandle":"loop_body"},
              {"id":"e4","source":"n1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        verify(messagingService, never()).send(any(), any(), anyBoolean());
        var output = MAPPER.readTree(stepsByNode(run.getId()).get("l1").getOutput());
        assertFalse(output.get("hasNext").asBoolean());
        assertEquals(0, output.get("itemCount").asInt());
    }

    @Test
    void loopContinueOnErrorSkipsFailedItemAndKeepsGoing() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "alpha"), detectionDto(2L, "bravo"))));
        doThrow(new RuntimeException("boom"))
            .when(messagingService).send(any(), argThat(m -> m != null && m.title().contains("alpha")), anyBoolean());
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets","continueOnError":true}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,"titleTemplate":"{{loop.item.title}}"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"n1","sourceHandle":"loop_body"},
              {"id":"e4","source":"n1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationMessage.class);
        verify(messagingService, times(2)).send(any(), captor.capture(), anyBoolean());
        List<String> titles = captor.getAllValues().stream().map(NotificationMessage::title).toList();
        assertTrue(titles.contains("alpha"));
        assertTrue(titles.contains("bravo"));

        var output = MAPPER.readTree(stepsByNode(run.getId()).get("l1").getOutput());
        assertEquals(1, output.get("itemsSucceeded").asInt());
        assertEquals(1, output.get("itemsFailed").asInt());
        assertEquals(1, output.get("errors").size());
        assertEquals("n1", output.get("errors").get(0).get("nodeId").asText());
    }

    @Test
    void loopContinueOnErrorFalseStopsWholeLoopAndFailsRun() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "alpha"), detectionDto(2L, "bravo"))));
        doThrow(new RuntimeException("boom"))
            .when(messagingService).send(any(), argThat(m -> m != null && m.title().contains("alpha")), anyBoolean());
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"p0Dets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"p0Dets","continueOnError":false}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,"titleTemplate":"{{loop.item.title}}"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"n1","sourceHandle":"loop_body"},
              {"id":"e4","source":"n1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("boom"));
        // The whole loop stops the instant the unhandled failure is seen — the 2nd item's
        // notification is never even attempted.
        verify(messagingService, times(1)).send(any(), any(), anyBoolean());
    }

    @Test
    void loopBodyWithAgentTaskGoesWaitingMidIteration() throws Exception {
        Asset ip = saveAsset("ip", "10.0.0.5");
        when(assetService.listByAql(any(), any(), any(), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(ip)));
        when(agentTaskService.createAll(eq(projectId), any(), any())).thenReturn(List.of(taskDto(701L)));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"ips","variableType":"asset","sources":[{"entityType":"asset","aql":"type != null"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"loop","config":{"variableName":"ips"}}},
              {"id":"a1","type":"ACTION_AGENT_TASK","data":{"label":"scan","config":{"poolId":1,"tool":"test-tool",
                "argsTemplate":{"targetsFrom":{"type":"workflow_variable","variableName":"ips"}}}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"a1","sourceHandle":"loop_body"},
              {"id":"e4","source":"a1","target":"l1"},
              {"id":"e5","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""", WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        // The loop body's ACTION_AGENT_TASK goes WAITING like it would anywhere else in the graph —
        // it's an ordinary step, at (nodeId="a1", the current iteration's path) — proving an async
        // node inside a loop body doesn't hang or crash the engine, just like a top-level one.
        assertEquals(WorkflowRunStatus.RUNNING, run.getStatus());
        WorkflowStepRun agentStep = stepRunRepo.findByWorkflowRunId(run.getId()).stream()
            .filter(s -> "a1".equals(s.getNodeId())).findFirst().orElseThrow();
        assertEquals(WorkflowStepStatus.WAITING, agentStep.getStatus());
        assertNotEquals("[]", agentStep.getIterationPath());
    }

    @Test
    void loopNestedRunsFullCrossProduct() throws Exception {
        when(detectionService.listByAql(any(), any(), eq("priority == P0"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(1L, "outer-a"), detectionDto(2L, "outer-b"))));
        when(detectionService.listByAql(any(), any(), eq("priority == P1"), any(), any(), anyInt(), anyInt()))
            .thenReturn(new PageImpl<>(List.of(detectionDto(11L, "inner-x"), detectionDto(12L, "inner-y"))));

        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pullOuter","config":{"variableName":"outerDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}},
              {"id":"l1","type":"LOOP","data":{"label":"outer","config":{"variableName":"outerDets"}}},
              {"id":"v2","type":"ASSIGN_VARIABLE","data":{"label":"pullInner","config":{"variableName":"innerDets","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P1"}]}}},
              {"id":"l2","type":"LOOP","data":{"label":"inner","config":{"variableName":"innerDets"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d,"titleTemplate":"{{loop.item.title}}"}}},
              {"id":"end1","type":"END","data":{"label":"done","config":{"result":"success"}}}
            ],"edges":[
              {"id":"e1","source":"t1","target":"v1"},
              {"id":"e2","source":"v1","target":"l1"},
              {"id":"e3","source":"l1","target":"v2","sourceHandle":"loop_body"},
              {"id":"e4","source":"v2","target":"l2"},
              {"id":"e5","source":"l2","target":"n1","sourceHandle":"loop_body"},
              {"id":"e6","source":"n1","target":"l2"},
              {"id":"e7","source":"l2","target":"l1","sourceHandle":"loop_done"},
              {"id":"e8","source":"l1","target":"end1","sourceHandle":"loop_done"}
            ]}""".formatted(integrationId), WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        // 2 outer items x 2 inner items each = 4 notifications — the inner loop re-runs in full for
        // every outer iteration (a fresh ASSIGN_VARIABLE + LOOP instance each time, since both sit
        // inside the outer body).
        verify(messagingService, times(4)).send(any(), any(), anyBoolean());
    }

    @Test
    void integrationCallNodeWaitsOnRegistryReturnedRef() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","integrationId":9,"action":"FIXTURE_ACTION"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"s1"}]}""",
            WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.RUNNING, run.getStatus());
        WorkflowStepRun callStep = stepsByNode(run.getId()).get("s1");
        assertEquals(WorkflowStepStatus.WAITING, callStep.getStatus());
        assertEquals("INTEGRATION_ACTION", callStep.getRefType());
        assertEquals(999L, callStep.getRefId());
        assertTrue(callStep.getInput().contains("fixture-type"));
    }

    @Test
    void integrationCallNodeRejectedOutsideProjectScopedWorkflow() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"fixture-type","integrationId":9,"action":"FIXTURE_ACTION"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"s1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("isn't usable from a"));
    }

    @Test
    void integrationCallNodeFailsCleanlyForUnregisteredType() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"ACTION_INTEGRATION_CALL","data":{"label":"call","config":{"integrationType":"nope","integrationId":9,"action":"WHATEVER"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"s1"}]}""",
            WorkflowScope.PROJECT, projectId);

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("has no registered handler"));
    }

    @Test
    void callWorkflowFiresASingleSubscriberAndRecordsParentLinkage() {
        Workflow sub = saveWorkflow("""
            {"nodes":[
              {"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}},
              {"id":"n1","type":"ACTION_NOTIFICATION","data":{"label":"notify","config":{"integrationId":%d}}}
            ],"edges":[{"id":"e1","source":"s1","target":"n1"}]}""".formatted(integrationId));
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""");

        WorkflowRun run = service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        WorkflowStepRun callStep = stepsByNode(run.getId()).get("c1");
        assertEquals(WorkflowStepStatus.COMPLETED, callStep.getStatus());
        assertTrue(callStep.getOutput().contains("\"subscriberCount\":1") || callStep.getOutput().contains("\"subscriberCount\": 1"));

        List<WorkflowRun> subRuns = runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertEquals(1, subRuns.size());
        assertEquals(WorkflowRunStatus.COMPLETED, subRuns.get(0).getStatus());
        assertEquals(callStep.getId(), subRuns.get(0).getParentStepRunId());
        assertEquals("topic:kev-check", subRuns.get(0).getTriggeredBy());
        verify(messagingService, atLeastOnce()).send(any(), any(), eq(true));
    }

    @Test
    void callWorkflowFiresEveryMatchingActiveSubscriberAndCompletesImmediately() {
        Workflow subA = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, projectId);
        saveCallTopicTrigger(subA.getId(), "s1", "kev-check");

        Workflow subB = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.ORGANIZATION, organizationId);
        saveCallTopicTrigger(subB.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}"""); // caller defaults to PLATFORM

        WorkflowRun run = service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        WorkflowStepRun callStep = stepsByNode(run.getId()).get("c1");
        assertEquals(WorkflowStepStatus.COMPLETED, callStep.getStatus());
        assertTrue(callStep.getOutput().contains("\"subscriberCount\":2") || callStep.getOutput().contains("\"subscriberCount\": 2"));

        assertEquals(1, runRepo.findByWorkflowIdOrderByStartedAtDesc(subA.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
        assertEquals(1, runRepo.findByWorkflowIdOrderByStartedAtDesc(subB.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    /** A PLATFORM caller reaches anything below it in the hierarchy. */
    @Test
    void callWorkflowAllowsAPlatformCallerToReachAProjectScopedSubscriber() {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, projectId);
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}"""); // caller defaults to PLATFORM scope

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(1, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowAllowsAnOrgCallerToReachItsOwnProject() {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, projectId);
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.ORGANIZATION, organizationId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(1, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowAllowsAProjectCallerToReachItself() {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, projectId);
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.PROJECT, projectId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(1, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowRejectsAnOrgCallerReachingADifferentOrg() {
        OrgAndProject other = createOrgAndProject();
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.ORGANIZATION, other.orgId());
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.ORGANIZATION, organizationId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowRejectsAnOrgCallerReachingAProjectInADifferentOrg() {
        OrgAndProject other = createOrgAndProject();
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, other.projectId());
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.ORGANIZATION, organizationId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowRejectsAProjectCallerReachingADifferentProject() {
        OrgAndProject other = createOrgAndProject();
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, other.projectId());
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.PROJECT, projectId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowRejectsAProjectCallerReachingItsOwnOrg() {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.ORGANIZATION, organizationId);
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.PROJECT, projectId);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowSkipsSubscribersOutsideVerticalScope() {
        OrgAndProject other = createOrgAndProject();
        Workflow outOfScopeSub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, other.projectId());
        saveCallTopicTrigger(outOfScopeSub.getId(), "s1", "kev-check");

        // Caller is ORGANIZATION-scoped (this test's own org) — the subscriber above lives in a
        // completely different org/project, which the vertical rule must never reach.
        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""",
            WorkflowScope.ORGANIZATION, organizationId);

        WorkflowRun run = service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(outOfScopeSub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    @Test
    void callWorkflowPassesParamsTemplateToSubscribers() {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""",
            WorkflowScope.PROJECT, projectId);
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");

        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check",
                "paramsTemplate":{"cve":"CVE-2024-9999"}}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""");

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        List<WorkflowRun> subRuns = runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertEquals(1, subRuns.size());
        assertTrue(subRuns.get(0).getContext().contains("CVE-2024-9999"));
    }

    @Test
    void callWorkflowCompletesEvenWithNoMatchingSubscribers() {
        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"nobody-listens"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""");

        WorkflowRun run = service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
    }

    @Test
    void callWorkflowSkipsACyclicSubscriberWithoutFailingTheStep() {
        // The broadcaster itself also subscribes to the topic it's about to broadcast — a
        // self-referential cycle assertNoCycleOrDepthLimitExceeded must catch per-subscriber,
        // without that one bad subscriber failing the whole (fire-and-forget) broadcast step.
        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"self-loop"}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"self-loop"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""");
        saveCallTopicTrigger(caller.getId(), "s1", "self-loop");

        WorkflowRun run = service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
        WorkflowStepRun callStep = stepsByNode(run.getId()).get("c1");
        assertEquals(WorkflowStepStatus.COMPLETED, callStep.getStatus());
        assertTrue(callStep.getOutput().contains("\"subscriberCount\":0") || callStep.getOutput().contains("\"subscriberCount\": 0"));
    }

    @Test
    void callWorkflowRejectsWhenDepthLimitExceeded() throws Exception {
        Workflow sub = saveWorkflow("""
            {"nodes":[{"id":"s1","type":"TRIGGER_CALL_TOPIC","data":{"label":"sub","config":{"topic":"kev-check"}}}],"edges":[]}""");
        saveCallTopicTrigger(sub.getId(), "s1", "kev-check");
        Workflow caller = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"c1","type":"ACTION_CALL_WORKFLOW","data":{"label":"call","config":{"topic":"kev-check"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"c1"}]}""");

        java.lang.reflect.Field depthField = WorkflowRunService.class.getDeclaredField("maxCallDepth");
        depthField.setAccessible(true);
        depthField.set(service, 0);

        service.start(caller.getId(), "t1", Map.of(), "manual", null);

        assertEquals(0, runRepo.findByWorkflowIdOrderByStartedAtDesc(sub.getId(), org.springframework.data.domain.PageRequest.of(0, 10)).getContent().size());
    }

    /** Minimal JDK-only stand-in for an external webhook receiver — no mock-server dependency
     *  exists in this codebase yet, and {@code com.sun.net.httpserver.HttpServer} is enough to
     *  capture the exact request an {@code ACTION_WEBHOOK_CALL} node sends. */
    private com.sun.net.httpserver.HttpServer startTestServer(int statusCode, java.util.concurrent.atomic.AtomicReference<byte[]> bodyOut,
                                                                java.util.concurrent.atomic.AtomicReference<String> signatureOut) throws Exception {
        var httpServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/hook", exchange -> {
            bodyOut.set(exchange.getRequestBody().readAllBytes());
            signatureOut.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            exchange.sendResponseHeaders(statusCode, -1);
            exchange.close();
        });
        httpServer.start();
        return httpServer;
    }

    @Test
    void webhookCallPostsRenderedBodyAndCompletes() throws Exception {
        var bodyRef = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var sigRef = new java.util.concurrent.atomic.AtomicReference<String>();
        var testServer = startTestServer(200, bodyRef, sigRef);
        try {
            Workflow wf = saveWorkflow("""
                {"nodes":[
                  {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
                  {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{
                    "url":"http://127.0.0.1:%d/hook","bodyTemplate":{"detectionId":"{{trigger.entityId}}"}}}}
                ],"edges":[{"id":"e1","source":"t1","target":"w1"}]}""".formatted(testServer.getAddress().getPort()));

            WorkflowRun run = service.start(wf.getId(), "t1", triggerContext(), "manual", null);

            assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
            assertNotNull(bodyRef.get());
            assertTrue(new String(bodyRef.get(), java.nio.charset.StandardCharsets.UTF_8).contains(String.valueOf(detectionId)));
            assertNull(sigRef.get());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void webhookCallSignsBodyWhenOutboundSecretConfigured() throws Exception {
        var bodyRef = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var sigRef = new java.util.concurrent.atomic.AtomicReference<String>();
        var testServer = startTestServer(200, bodyRef, sigRef);
        try {
            Workflow wf = saveWorkflow("""
                {"nodes":[
                  {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
                  {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{
                    "url":"http://127.0.0.1:%d/hook","bodyTemplate":{"x":"y"}}}}
                ],"edges":[{"id":"e1","source":"t1","target":"w1"}]}""".formatted(testServer.getAddress().getPort()));
            when(workflowService.getOutboundWebhookSecretPlaintext(wf.getId(), "w1"))
                .thenReturn(java.util.Optional.of("shh-its-a-secret"));

            WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

            assertEquals(WorkflowRunStatus.COMPLETED, run.getStatus());
            String expected = new com.martecyber.ares.webhooks.WebhookSignatureService().sign("shh-its-a-secret", bodyRef.get());
            assertEquals(expected, sigRef.get());
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void webhookCallFailsOnNon2xxResponse() throws Exception {
        var bodyRef = new java.util.concurrent.atomic.AtomicReference<byte[]>();
        var sigRef = new java.util.concurrent.atomic.AtomicReference<String>();
        var testServer = startTestServer(500, bodyRef, sigRef);
        try {
            Workflow wf = saveWorkflow("""
                {"nodes":[
                  {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
                  {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{
                    "url":"http://127.0.0.1:%d/hook"}}}
                ],"edges":[{"id":"e1","source":"t1","target":"w1"}]}""".formatted(testServer.getAddress().getPort()));

            WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

            assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
            assertTrue(run.getError().contains("500"));
        } finally {
            testServer.stop(0);
        }
    }

    @Test
    void webhookCallFailsOnUnreachableHost() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"w1","type":"ACTION_WEBHOOK_CALL","data":{"label":"call","config":{
                "url":"http://127.0.0.1:1/hook"}}}
            ],"edges":[{"id":"e1","source":"t1","target":"w1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertNotNull(run.getError());
    }

    @Test
    void assignVariableQueryRejectedAtPlatformScope() {
        Workflow wf = saveWorkflow("""
            {"nodes":[
              {"id":"t1","type":"TRIGGER_MANUAL","data":{"label":"start","config":{}}},
              {"id":"v1","type":"ASSIGN_VARIABLE","data":{"label":"pull","config":{"variableName":"x","variableType":"detection","sources":[{"entityType":"detection","aql":"priority == P0"}]}}}
            ],"edges":[{"id":"e1","source":"t1","target":"v1"}]}""");

        WorkflowRun run = service.start(wf.getId(), "t1", Map.of(), "manual", null);

        assertEquals(WorkflowRunStatus.FAILED, run.getStatus());
        assertTrue(run.getError().contains("platform-scoped"));
    }

    private Map<String, WorkflowStepRun> stepsByNode(Long runId) {
        Map<String, WorkflowStepRun> out = new LinkedHashMap<>();
        for (WorkflowStepRun s : stepRunRepo.findByWorkflowRunId(runId)) out.put(s.getNodeId(), s);
        return out;
    }
}
