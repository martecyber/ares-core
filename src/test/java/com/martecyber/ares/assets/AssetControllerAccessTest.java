package com.martecyber.ares.assets;

import com.martecyber.ares.assets.dto.CreateWebEndpointHttpSampleRequest;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.users.OrgScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Every org-scoped asset endpoint must reject a user without access to the target organization
 * BEFORE reading or writing anything. MSSP_OPERATOR is org-restricted (OrgScopeService), so a
 * role check alone (@PreAuthorize) is not enough.
 */
class AssetControllerAccessTest {

    private AssetService service;
    private AssetRelationshipService relSvc;
    private WebEndpointHttpSampleService sampleSvc;
    private AssetMergeService mergeSvc;
    private ServiceVisibilityRepository visibilityRepo;
    private OrgScopeService orgScope;
    private AssetController controller;
    private final Authentication auth = mock(Authentication.class);

    @BeforeEach
    void setUp() {
        service = mock(AssetService.class);
        relSvc = mock(AssetRelationshipService.class);
        sampleSvc = mock(WebEndpointHttpSampleService.class);
        mergeSvc = mock(AssetMergeService.class);
        visibilityRepo = mock(ServiceVisibilityRepository.class);
        orgScope = mock(OrgScopeService.class);
        controller = new AssetController(service, relSvc, sampleSvc, mergeSvc, visibilityRepo,
            mock(AssetToolSightingService.class), orgScope, mock(ScopeClassifyScheduler.class));

        AccessDeniedException denied = new AccessDeniedException("no");
        doThrow(denied).when(orgScope).assertOrgAccess(any(), any());
        doThrow(denied).when(orgScope).assertProjectAccess(any(), any());
        doThrow(denied).when(service).assertAccess(anyLong());
        doThrow(denied).when(service).assertAccess(anyCollection());
    }

    @Test
    void relationshipReadsAreOrgScoped() {
        assertThrows(AccessDeniedException.class, () -> controller.listRelationships(7L));
        assertThrows(AccessDeniedException.class, () -> controller.listRelationships(2L, null, auth));
        assertThrows(AccessDeniedException.class, () -> controller.listRelationships(null, 3L, auth));
        assertThrows(AccessDeniedException.class, () -> controller.neighborhood(2L, 7L, 4, auth));
        assertThrows(AccessDeniedException.class, () -> controller.findReachable(2L, List.of(7L), auth));
        verifyNoInteractions(relSvc);
    }

    @Test
    void relationshipListNeedsAScope() {
        assertThrows(ResponseStatusException.class, () -> controller.listRelationships(null, null, auth));
        verifyNoInteractions(relSvc);
    }

    @Test
    void mergeIsOrgScoped() {
        assertThrows(AccessDeniedException.class,
            () -> controller.merge(7L, new AssetMergeService.MergeRequest(8L, List.of())));
        verifyNoInteractions(mergeSvc);
    }

    @Test
    void bulkLookupsAreOrgScoped() {
        assertThrows(AccessDeniedException.class, () -> controller.visibilityAggregates(List.of(7L, 8L)));
        assertThrows(AccessDeniedException.class, () -> controller.serviceHosts(List.of(7L)));
        verifyNoInteractions(visibilityRepo);
        verify(service, never()).findHostsForServices(any());
    }

    @Test
    void httpSamplesAreOrgScoped() {
        CreateWebEndpointHttpSampleRequest req = new CreateWebEndpointHttpSampleRequest("l", "req", "resp", null);
        assertThrows(AccessDeniedException.class, () -> controller.listHttpSamples(7L));
        assertThrows(AccessDeniedException.class, () -> controller.createHttpSample(7L, req));
        assertThrows(AccessDeniedException.class, () -> controller.updateHttpSample(7L, 1L, req));
        assertThrows(AccessDeniedException.class, () -> controller.deleteHttpSample(7L, 1L));
        verifyNoInteractions(sampleSvc);
    }
}
