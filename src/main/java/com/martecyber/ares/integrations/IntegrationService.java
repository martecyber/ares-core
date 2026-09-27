package com.martecyber.ares.integrations;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.integrations.tools.IntegrationClient;
import com.martecyber.ares.integrations.dto.*;
import com.martecyber.ares.integrations.grants.IntegrationGrant;
import com.martecyber.ares.integrations.grants.IntegrationGrantRepository;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Integration service class with operations related to integrations in general
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    private final IntegrationRepository repo;
    private final IntegrationGrantRepository grantRepo;
    private final CredentialEncryptionService encryption;
    /** {@link java.util.concurrent.CopyOnWriteArrayList}, not the plain list Spring injects here
     *  at startup — {@code com.martecyber.ares.plugins.PluginLoader} calls {@link #registerClient}
     *  to add a plugin-provided {@link IntegrationClient} after the app has already finished
     *  starting (a plugin JAR installed at runtime was never on the classpath this constructor's
     *  own injection saw), so reads here (every {@link #findClient} call) must be safe to run
     *  concurrently with that late write. */
    private final List<IntegrationClient> clients;
    private final ObjectMapper objectMapper;

    public IntegrationService(IntegrationRepository repo,
                              IntegrationGrantRepository grantRepo,
                              CredentialEncryptionService encryption,
                              List<IntegrationClient> clients,
                              ObjectMapper objectMapper) {
        this.repo = repo;
        this.grantRepo = grantRepo;
        this.encryption = encryption;
        this.clients = new java.util.concurrent.CopyOnWriteArrayList<>(clients);
        this.objectMapper = objectMapper;
    }

    /** See {@link #clients}'s own doc comment. No duplicate-rejection here unlike {@code
     *  IntegrationActionRegistry#registerLate} — {@link IntegrationClient#supports} is checked in
     *  {@code #findClient}'s own declaration order (first match wins), so a plugin registering a
     *  type a built-in client already supports would simply never be reachable rather than
     *  breaking startup; a plugin has no legitimate reason to do that, and it's not worth the
     *  same startup-time hard-fail this method's caller (PluginLoader) can't easily surface the
     *  way IntegrationActionRegistry's constructor-time throw does. */
    public void registerClient(IntegrationClient client) {
        clients.add(client);
    }

    /** Counterpart to {@link #registerClient} — a plugin being disabled stops offering "Test
     *  connection" for its type without needing a restart. Removes by identity, not by {@code
     *  supports()} match, so disabling one plugin never accidentally removes a different
     *  client that happens to support the same type string. */
    public void unregisterClient(IntegrationClient client) {
        clients.remove(client);
    }


    /**
     * Lists the integrations available
     *
     * @param organizationId	Integrations related to organization wiith specific id
     * @param type				Integration types to be retrieved
     * @param status			Status of the integration
     * @param page				Page of the list to retrieve
     * @param size				Size of the page to retrieve
     * @return					The list of integrations filtered by the parameters
     *
     * TODO Default values should be added to the parameters.
     */
    public Page<IntegrationDto> list(Long organizationId, String type, String status, int page, int size) {
        var p = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 200));
        return repo.filter(organizationId, blank(type), blank(status), p).map(IntegrationDto::from);
    }

    /**
     * Retrieves an specific integration
     *
     * @param id	The id of the specific integration to be retrieved
     * @return		The integration with the specified id
     */
    public IntegrationDto get(Long id) {
        return repo.findById(id).map(IntegrationDto::from)
            .orElseThrow(() -> NotFoundException.of("integration", id));
    }

    /**
     * Creates a new integration
     *
     * @param req	The request to create an integration
     * @return		The integration dto of the recently created integration
     */
    @Transactional
    public IntegrationDto create(CreateIntegrationRequest req) {
        Integration i = new Integration();
        i.setToolId(req.toolId());
        i.setName(req.name());
        i.setType(req.type());
        i.setScope(req.effectiveScope());
        i.setOrganizationId(req.organizationId());
        i.setStatus("active");
        i.setSettings(req.settings());
        if (req.credentials() != null && !req.credentials().isEmpty()) {
            storeCredentials(i, req.credentials());
        }
        OffsetDateTime now = OffsetDateTime.now();
        i.setCreatedAt(now);
        i.setUpdatedAt(now);
        return IntegrationDto.from(repo.save(i));
    }

    /**
     * Updates an integration
     *
     * @param id	Id of the integration to be updated
     * @param req	Request to updated an integration
     * @return		The integration dto of the recently updated integration
     */
    @Transactional
    public IntegrationDto update(Long id, UpdateIntegrationRequest req) {
        Integration i = repo.findById(id).orElseThrow(() -> NotFoundException.of("integration", id));
        if (req.name() != null)     i.setName(req.name());
        if (req.status() != null)   i.setStatus(req.status());
        if (req.settings() != null) i.setSettings(req.settings());
        if (req.credentials() != null && !req.credentials().isEmpty()) {
            // Merge with existing so partial updates (e.g. only token) don't drop other fields
            Map<String, String> merged = new java.util.HashMap<>(loadCredentials(i));
            merged.putAll(req.credentials());
            storeCredentials(i, merged);
        }
        i.setUpdatedAt(OffsetDateTime.now());
        return IntegrationDto.from(repo.save(i));
    }

    /**
     * Records the outcome of a sync attempt against an integration: sets {@code lastSyncAt} to
     * now and {@code connectionStatus} to the given value. Generic across every sync-style
     * integration type — introduced so a sync job handler never needs {@code
     * IntegrationRepository}/{@code Integration} directly just to update these two bookkeeping
     * fields (previously every such handler did its own read-mutate-save on the raw entity).
     *
     * @param id                Id of the integration whose sync just completed (or failed)
     * @param connectionStatus  e.g. "success" / "error"
     */
    @Transactional
    public void updateSyncStatus(Long id, String connectionStatus) {
        Integration i = repo.findById(id).orElseThrow(() -> NotFoundException.of("integration", id));
        i.setLastSyncAt(OffsetDateTime.now());
        i.setConnectionStatus(connectionStatus);
        repo.save(i);
    }

    /**
     * Deletes an integration
     *
     * @param id	Id of the integration to be deleted
     */
    @Transactional
    public void delete(Long id) {
        if (!repo.existsById(id)) throw NotFoundException.of("integration", id);
        repo.deleteById(id);
    }


    /**
     * Test the connection of an integration
     *
     * @param id	Id of the integration to test connection
     * @return		Dto of the integration with the updated connection status
     *
     * TODO The method at this level shouldn't have specific code for specific integrations.
     */
    @Transactional
    public IntegrationDto testConnection(Long id) {
        Integration i = repo.findById(id).orElseThrow(() -> NotFoundException.of("integration", id));
        IntegrationClient client = findClient(i.getType());
        Map<String, String> creds = loadCredentials(i);
        String status;
        log.info("Testing connection for integration id={} type={}", id, i.getType());
        long t0 = System.currentTimeMillis();
        String errorMessage = null;
        try {
            client.testConnection(i.getSettings(), creds);
            status = "success";
            // Detect available capabilities and store them in settings, when this client type
            // supports the concept (see IntegrationClient#detectCapabilities's own doc).
            List<String> caps = client.detectCapabilities(i.getSettings(), creds);
            if (!caps.isEmpty()) {
                i.setSettings(mergeCapabilitiesIntoSettings(i.getSettings(), caps));
            }
        } catch (Exception e) {
            status = "failed";
            errorMessage = e.getMessage();
            log.warn("Connection test failed for integration id={} type={} after {}ms — {}",
                id, i.getType(), System.currentTimeMillis() - t0, e.getMessage());
        }
        log.info("Connection test result for integration id={} type={} status={} elapsed={}ms",
            id, i.getType(), status, System.currentTimeMillis() - t0);
        i.setConnectionStatus(status);
        i.setConnectionError(errorMessage);
        i.setUpdatedAt(OffsetDateTime.now());
        return IntegrationDto.from(repo.save(i));
    }

    private String mergeCapabilitiesIntoSettings(String existing, List<String> caps) {
        try {
            ObjectNode root = (existing != null && !existing.isBlank())
                ? (ObjectNode) objectMapper.readTree(existing)
                : objectMapper.createObjectNode();
            ArrayNode arr = objectMapper.createArrayNode();
            caps.forEach(arr::add);
            root.set("detectedCapabilities", arr);
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) { return existing; }
    }

    /**
     * Lists the grants associated with an integration
     * @param integrationId	Id of the integration to list their grants
     * @return				Map with the grants associated to the integration
     */
    public List<IntegrationGrantDto> listGrants(Long integrationId) {
        if (!repo.existsById(integrationId)) throw NotFoundException.of("integration", integrationId);
        return grantRepo.findByIntegrationId(integrationId).stream()
            .map(IntegrationGrantDto::from).toList();
    }

    /**
     *
     * @param integrationId
     * @param req
     * @return
     */
    @Transactional
    public IntegrationGrantDto createGrant(Long integrationId, CreateGrantRequest req) {
        if (!repo.existsById(integrationId)) throw NotFoundException.of("integration", integrationId);
        IntegrationGrant g = new IntegrationGrant();
        g.setIntegrationId(integrationId);
        g.setOrganizationId(req.organizationId());
        g.setProjectId(req.projectId());
        g.setCapabilities(req.capabilities() != null ? req.capabilities() : List.of());
        g.setAccountId(req.accountId());
        g.setAccountName(req.accountName());
        g.setTaskId(req.taskId());
        g.setTaskName(req.taskName());
        OffsetDateTime now = OffsetDateTime.now();
        g.setCreatedAt(now);
        g.setUpdatedAt(now);
        return IntegrationGrantDto.from(grantRepo.save(g));
    }

    @Transactional
    public void revokeGrant(Long integrationId, Long grantId) {
        IntegrationGrant g = grantRepo.findById(grantId)
            .orElseThrow(() -> NotFoundException.of("integration_grant", grantId));
        if (!g.getIntegrationId().equals(integrationId))
            throw NotFoundException.of("integration_grant", grantId);
        grantRepo.deleteById(grantId);
    }

    // ── MSSP accounts ─────────────────────────────────────────────────────────

    public List<Map<String, String>> listMsspAccounts(Long integrationId) {
        return listPickerItems(integrationId, "MSSP accounts");
    }

    // ── Greenbone tasks ────────────────────────────────────────────────────────

    public List<Map<String, String>> listGreenboneTasks(Long integrationId) {
        return listPickerItems(integrationId, "Greenbone tasks");
    }

    /** Generic across every {@link com.martecyber.ares.integrations.tools.IntegrationClient}
     *  implementation — including a plugin-provided one — via {@link
     *  com.martecyber.ares.integrations.tools.IntegrationClient#listPickerItems}, so this never
     *  needs a cast to a concrete client class that might not even be on ares-core's classpath
     *  (e.g. TenableClient/GreenboneClient, both plugin-provided). */
    private List<Map<String, String>> listPickerItems(Long integrationId, String what) {
        Integration i = repo.findById(integrationId)
            .orElseThrow(() -> NotFoundException.of("integration", integrationId));
        Map<String, String> creds = loadCredentials(i);
        try {
            return findClient(i.getType()).listPickerItems(i.getSettings(), creds);
        } catch (Exception e) {
            throw new RuntimeException("Failed to list " + what + ": " + e.getMessage(), e);
        }
    }

    // ── Project-scoped access ──────────────────────────────────────────────

    /** Returns integrations accessible from the given project, resolved via grants. */
    public List<IntegrationDto> listForProject(Long engId, Long orgId) {
        List<IntegrationGrant> grants = grantRepo.findActiveForProject(orgId, engId);
        List<Long> ids = grants.stream().map(IntegrationGrant::getIntegrationId).distinct().toList();
        return repo.findAllById(ids).stream().map(IntegrationDto::from).toList();
    }

    /** Resolves the grant for a specific integration + project, or throws if not granted.
     *  Assumes at most one meaningful grant per (integration, project) — true for every
     *  integration type except Greenbone, which can have several (one per granted task);
     *  use {@link #resolveGrants} there instead. */
    public IntegrationGrant resolveGrant(Long integrationId, Long engId, Long orgId) {
        return grantRepo.findActiveForProject(orgId, engId).stream()
            .filter(g -> g.getIntegrationId().equals(integrationId))
            .findFirst()
            .orElseThrow(() -> new com.martecyber.ares.common.ConflictException(
                "Integration " + integrationId + " is not granted to this project"));
    }

    /** All active grants for a specific integration + project (plural) — Greenbone can have
     *  several grants for the same project, one per granted GVM task. */
    public List<IntegrationGrant> resolveGrants(Long integrationId, Long engId, Long orgId) {
        List<IntegrationGrant> grants = grantRepo.findActiveForProject(orgId, engId).stream()
            .filter(g -> g.getIntegrationId().equals(integrationId))
            .toList();
        if (grants.isEmpty())
            throw new com.martecyber.ares.common.ConflictException(
                "Integration " + integrationId + " is not granted to this project");
        return grants;
    }

    // ── Credential helpers ────────────────────────────────────────────────────

    public Map<String, String> loadCredentials(Integration i) {
        if (i.getCredentials() == null || i.getCredentialIv() == null) return Map.of();
        try {
            String json = encryption.decrypt(i.getCredentials(), i.getCredentialIv());
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(json, Map.class);
            return creds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to load credentials for integration " + i.getId(), e);
        }
    }

    public Map<String, String> loadCredentials(Long integrationId) {
        Integration i = repo.findById(integrationId)
            .orElseThrow(() -> NotFoundException.of("integration", integrationId));
        return loadCredentials(i);
    }

    // ── Client resolution ─────────────────────────────────────────────────────

    public IntegrationClient findClient(String type) {
        return clients.stream()
            .filter(c -> c.supports(type))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No client found for integration type: " + type));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void storeCredentials(Integration i, Map<String, String> creds) {
        try {
            String json = objectMapper.writeValueAsString(creds);
            CredentialEncryptionService.Encrypted enc = encryption.encrypt(json);
            i.setCredentials(enc.ciphertext());
            i.setCredentialIv(enc.iv());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt credentials", e);
        }
    }

    private static String blank(String s) { return (s == null || s.isBlank()) ? null : s; }
}
