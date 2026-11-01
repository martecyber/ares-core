package com.martecyber.ares.security;

import com.martecyber.ares.affections.AffectionController;
import com.martecyber.ares.agents.pools.ProjectAgentPoolController;
import com.martecyber.ares.agents.tasks.AgentTaskController;
import com.martecyber.ares.agents.tasks.AgentTaskService;
import com.martecyber.ares.agents.tasks.GlobalAgentTaskController;
import com.martecyber.ares.agents.tasks.dto.AgentTaskDtos;
import com.martecyber.ares.agents.tasks.templates.AgentTaskTemplateController;
import com.martecyber.ares.assets.AssetController;
import com.martecyber.ares.assets.AssetService;
import com.martecyber.ares.detections.DetectionController;
import com.martecyber.ares.detections.DetectionService;
import com.martecyber.ares.findings.FindingController;
import com.martecyber.ares.findings.FindingService;
import com.martecyber.ares.integrations.IntegrationController;
import com.martecyber.ares.integrations.IntegrationService;
import com.martecyber.ares.integrations.dto.IntegrationDto;
import com.martecyber.ares.jobs.JobController;
import com.martecyber.ares.organizations.OrganizationController;
import com.martecyber.ares.projects.ProjectController;
import com.martecyber.ares.projects.ProjectRetestController;
import com.martecyber.ares.projects.rules.ProjectRuleController;
import com.martecyber.ares.projects.testing.ProjectTestingGuideController;
import com.martecyber.ares.reporting.ReportController;
import com.martecyber.ares.reporting.ReportService;
import com.martecyber.ares.users.OrgScopeService;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Org isolation guard: MSSP_OPERATOR is restricted to its assigned organizations
 * ({@link OrgScopeService}), so a role check in {@code @PreAuthorize} is not enough. Every endpoint
 * listed here performs its organization check in the controller itself (or through a service getter
 * that is the check), so with a caller that has access to NO organization each one must answer 403
 * before touching any data. Endpoints whose check lives deeper inside their service are covered by
 * that service's own tests instead.
 */
class OrgIsolationEndpointsTest {

    private static final Map<Class<?>, List<String>> GUARDED = new LinkedHashMap<>();
    static {
        GUARDED.put(AssetController.class, List.of("findReachable", "neighborhood", "listRelationships",
            "addRelationship", "removeRelationship", "merge", "visibilityAggregates", "serviceHosts",
            "listVisibility", "listHttpSamples", "createHttpSample", "updateHttpSample", "deleteHttpSample",
            "listToolSightings", "listToolSightingProjects", "listExternalIds"));
        GUARDED.put(ProjectController.class, List.of("get", "create", "previewCode", "complete", "approveIteration",
            "addScope", "removeScope", "deriveAssetsFromScope", "getMyRole", "listAssets", "addAsset", "removeAsset",
            "hideRelationship", "addAssetManually", "classifyScope", "classifyScopeStatus", "getScopeStatuses",
            "setOverride", "clearOverride", "explainScope", "listIterations", "monitorStats"));
        GUARDED.put(FindingController.class, List.of("get", "sendEmail", "emailPreview", "suggestedRecipients"));
        GUARDED.put(DetectionController.class, List.of("get", "delete", "createHttpSample",
            "updateHttpSample", "deleteHttpSample"));
        GUARDED.put(ReportController.class, List.of("get", "create", "generateLegacy", "generateDocument",
            "sendEmail", "exportJson", "preview"));
        GUARDED.put(ProjectRuleController.class, List.of("list", "create", "update", "delete"));
        GUARDED.put(ProjectTestingGuideController.class, List.of("list", "assign", "resync", "remove", "updateItem"));
        GUARDED.put(AgentTaskController.class, List.of("list", "previewTargets", "create", "cancel"));
        GUARDED.put(ProjectAgentPoolController.class, List.of("list"));
        GUARDED.put(GlobalAgentTaskController.class, List.of("get"));
        GUARDED.put(AgentTaskTemplateController.class, List.of("fromTask"));
        GUARDED.put(JobController.class, List.of("get", "create", "update", "cancel"));
        GUARDED.put(IntegrationController.class, List.of("get", "listGrants"));
        GUARDED.put(OrganizationController.class, List.of("listOperators"));
        GUARDED.put(com.martecyber.ares.agents.pools.AgentPoolController.class, List.of("createGrant", "revokeGrant"));
    }

    @TestFactory
    Stream<DynamicTest> guardedEndpointsDenyACallerWithoutOrgAccess() {
        List<DynamicTest> tests = new ArrayList<>();
        GUARDED.forEach((controllerClass, names) -> {
            Object controller = build(controllerClass, new HashMap<>());
            for (String methodName : names) {
                List<Method> overloads = Arrays.stream(controllerClass.getDeclaredMethods())
                    .filter(m -> m.getName().equals(methodName) && Modifier.isPublic(m.getModifiers()) && isEndpoint(m))
                    .toList();
                assertFalse(overloads.isEmpty(), controllerClass.getSimpleName() + "." + methodName + " not found");
                for (Method m : overloads) {
                    String name = controllerClass.getSimpleName() + "." + methodName + Arrays.toString(m.getParameterTypes());
                    tests.add(DynamicTest.dynamicTest(name, () -> {
                        Throwable thrown = assertThrows(InvocationTargetException.class,
                            () -> m.invoke(controller, args(m)), name + " returned normally").getCause();
                        assertInstanceOf(AccessDeniedException.class, thrown,
                            name + " must deny a caller without org access, but threw " + thrown);
                    }));
                }
            }
        });
        return tests.stream();
    }

    private static boolean isEndpoint(Method m) {
        return m.isAnnotationPresent(GetMapping.class) || m.isAnnotationPresent(PostMapping.class)
            || m.isAnnotationPresent(PutMapping.class) || m.isAnnotationPresent(PatchMapping.class)
            || m.isAnnotationPresent(DeleteMapping.class) || m.isAnnotationPresent(RequestMapping.class);
    }

    /** Instantiates the controller with a mock per constructor dependency, wired to deny everything. */
    private static Object build(Class<?> c, Map<Class<?>, Object> mocks) {
        Constructor<?> ctor = c.getConstructors()[0];
        Object[] deps = Arrays.stream(ctor.getParameterTypes()).map(t -> mocks.computeIfAbsent(t, Deny::mock)).toArray();
        try {
            return ctor.newInstance(deps);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object[] args(Method m) {
        return Arrays.stream(m.getParameterTypes()).map(OrgIsolationEndpointsTest::sample).toArray();
    }

    private static Object sample(Class<?> t) {
        if (t == Long.class || t == long.class) return 1L;
        if (t == Integer.class || t == int.class) return 10;
        if (t == Boolean.class || t == boolean.class) return false;
        if (t == String.class) return "2026-01-01";
        if (t == List.class || t == Collection.class) return List.of(1L);
        if (t == Set.class) return Set.of(1L);
        if (t == Map.class) return new HashMap<>(Map.of("status", "in_scope"));
        if (t == Authentication.class) return mock(Authentication.class);
        if (t.isRecord() || !t.isInterface()) {
            try { return mock(t); } catch (Exception e) { return null; }
        }
        return mock(t);
    }

    /** Mock factory: a no-access OrgScopeService, and the services whose own getters are the access check. */
    static final class Deny {
        static Object mock(Class<?> t) {
            return mockDenying(t);
        }

        private static Object mockDenying(Class<?> t) {
            Object m = org.mockito.Mockito.mock(t);
            AccessDeniedException denied = new AccessDeniedException("no access");
            if (m instanceof OrgScopeService o) {
                doThrow(denied).when(o).assertOrgAccess(any(), any());
                doThrow(denied).when(o).assertProjectAccess(any(), any());
                doThrow(denied).when(o).assertFindingAccess(any(), any());
                doThrow(denied).when(o).assertAffectionAccess(any(), any());
                doThrow(denied).when(o).assertCurrentOrgAccess(any());
                doThrow(denied).when(o).assertCurrentProjectAccess(any());
                doThrow(denied).when(o).assertCurrentCanSeeOrgOwned(any());
                when(o.currentAccessibleOrgIds()).thenReturn(Set.of());
                when(o.accessibleOrgIds(any())).thenReturn(Set.of());
            } else if (m instanceof AssetService s) {
                doThrow(denied).when(s).assertAccess(anyLong());
                doThrow(denied).when(s).assertAccess(anyCollection());
                when(s.get(any())).thenThrow(denied);
            } else if (m instanceof FindingService s) {
                when(s.get(any())).thenThrow(denied);
            } else if (m instanceof DetectionService s) {
                when(s.get(any())).thenThrow(denied);
            } else if (m instanceof ReportService s) {
                when(s.get(any())).thenThrow(denied);
            } else if (m instanceof IntegrationService s) {
                when(s.get(any())).thenReturn(new IntegrationDto(1L, null, "x", "x", "ORGANIZATION", 99L,
                    null, null, null, null, null, null, null));
            } else if (m instanceof AgentTaskService s) {
                AgentTaskDtos.GlobalTaskDto dto = org.mockito.Mockito.mock(AgentTaskDtos.GlobalTaskDto.class);
                when(dto.organizationId()).thenReturn(99L);
                when(s.getGlobal(any())).thenReturn(dto);
            } else if (m instanceof com.martecyber.ares.projects.ProjectService s) {
                when(s.get(any(), any())).thenThrow(denied);
            }
            return m;
        }
    }
}
