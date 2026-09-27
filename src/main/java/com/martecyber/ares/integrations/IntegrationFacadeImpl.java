package com.martecyber.ares.integrations;

import com.martecyber.ares.integrations.dto.IntegrationDto;
import com.martecyber.ares.integrations.grants.IntegrationGrant;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Thin adapter exposing {@link IntegrationService} to plugins as the {@code ares-sdk}-owned
 *  {@link IntegrationFacade} — no new business logic beyond DTO/entity translation, so a plugin
 *  never needs {@code IntegrationRepository}/{@code Integration}/{@code IntegrationGrant}
 *  (ares-core-internal JPA types) on its classpath. */
@Component
class IntegrationFacadeImpl implements IntegrationFacade {

    private final IntegrationService integrationService;

    IntegrationFacadeImpl(IntegrationService integrationService) {
        this.integrationService = integrationService;
    }

    @Override
    public List<IntegrationView> list(Long organizationId, String type, String status, int page, int size) {
        return integrationService.list(organizationId, type, status, page, size)
            .map(IntegrationFacadeImpl::toView).getContent();
    }

    @Override
    public List<IntegrationView> listForProject(Long projectId, Long organizationId) {
        return integrationService.listForProject(projectId, organizationId).stream()
            .map(IntegrationFacadeImpl::toView).toList();
    }

    @Override
    public IntegrationView get(Long integrationId) {
        return toView(integrationService.get(integrationId));
    }

    @Override
    public Map<String, String> loadCredentials(Long integrationId) {
        return integrationService.loadCredentials(integrationId);
    }

    @Override
    public void recordSyncResult(Long integrationId, String connectionStatus) {
        integrationService.updateSyncStatus(integrationId, connectionStatus);
    }

    @Override
    public GrantView resolveGrant(Long integrationId, Long projectId, Long organizationId) {
        return toGrantView(integrationService.resolveGrant(integrationId, projectId, organizationId));
    }

    @Override
    public List<GrantView> resolveGrants(Long integrationId, Long projectId, Long organizationId) {
        return integrationService.resolveGrants(integrationId, projectId, organizationId).stream()
            .map(IntegrationFacadeImpl::toGrantView).toList();
    }

    private static IntegrationView toView(IntegrationDto dto) {
        return new IntegrationView(dto.id(), dto.type(), dto.name(), dto.settings());
    }

    private static GrantView toGrantView(IntegrationGrant g) {
        return new GrantView(g.getId(), g.getAccountId(), g.getAccountName(), g.getTaskId(), g.getTaskName());
    }
}
