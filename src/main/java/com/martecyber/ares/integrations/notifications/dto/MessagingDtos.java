package com.martecyber.ares.integrations.notifications.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.martecyber.ares.integrations.notifications.MessagingIntegration;

public final class MessagingDtos {
    private MessagingDtos() {}

    /** Public view of an integration — config is *never* echoed back verbatim; secrets (webhook
     *  URLs, bot tokens, passwords) stay server-side. {@code emailConfig} is the one deliberate
     *  exception: an email integration's host/port/username/fromAddress/useTls aren't secrets
     *  (only its password is), and without surfacing them the edit dialog had nothing to prefill
     *  — every field looked blank even though a real SMTP config was already stored, which is
     *  also what made the old blind "send whatever's in the form" update destructive (see
     *  MessagingService#update). Populated only for kind == email; null otherwise. */
    public record IntegrationDto(
        Long id, String name, String kind, boolean enabled,
        OffsetDateTime createdAt, OffsetDateTime updatedAt,
        EmailConfigDto emailConfig
    ) {
        public static IntegrationDto from(MessagingIntegration i) {
            return from(i, null);
        }

        public static IntegrationDto from(MessagingIntegration i, EmailConfigDto emailConfig) {
            return new IntegrationDto(i.getId(), i.getName(), i.getKind(), i.isEnabled(),
                i.getCreatedAt(), i.getUpdatedAt(), emailConfig);
        }
    }

    /** Non-secret slice of an email integration's config, for prefilling the edit dialog.
     *  Deliberately excludes {@code password}. */
    public record EmailConfigDto(String host, Integer port, String username, String fromAddress, Boolean useTls) {}

    public record CreateIntegrationRequest(String name, String kind, Map<String, Object> config) {}
    public record UpdateIntegrationRequest(String name, Boolean enabled, Map<String, Object> config) {}

    /** {@code to}/{@code cc}/{@code bcc} are email-kind only — every other transport's
     *  destination is fixed by the integration's own config, so the "Send test" UI only prompts
     *  for an address when testing an email integration. */
    public record TestRequest(String title, String body, String severity,
                               List<String> to, List<String> cc, List<String> bcc) {}

    public record GrantDto(Long id, Long integrationId, Long organizationId, Long projectId,
                           boolean active, java.time.OffsetDateTime createdAt) {
        public static GrantDto from(com.martecyber.ares.integrations.notifications.MessagingIntegrationGrant g) {
            return new GrantDto(g.getId(), g.getIntegrationId(), g.getOrganizationId(),
                g.getProjectId(), g.isActive(), g.getCreatedAt());
        }
    }
    public record CreateGrantRequest(Long organizationId, Long projectId) {}
}

