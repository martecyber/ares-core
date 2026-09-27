package com.martecyber.ares.projects.rules;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.parser.AqlParseException;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.projects.rules.dto.CreateProjectRuleRequest;
import com.martecyber.ares.projects.rules.dto.ProjectRuleDto;
import com.martecyber.ares.projects.rules.dto.UpdateProjectRuleRequest;
import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProjectRuleService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> VALID_TYPES = Set.of(
        "time_window", "rate_limit", "required_header", "required_user_agent",
        "severity_override", "max_concurrency", "excluded_vuln_type", "email_recipients"
    );

    private final ProjectRuleRepository repo;

    public ProjectRuleService(ProjectRuleRepository repo) {
        this.repo = repo;
    }

    public List<ProjectRuleDto> list(Long projectId) {
        return repo.findByProjectId(projectId).stream().map(ProjectRuleDto::from).toList();
    }

    @Transactional
    public ProjectRuleDto create(Long projectId, CreateProjectRuleRequest req) {
        if (req.ruleType() == null || !VALID_TYPES.contains(req.ruleType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Invalid ruleType. Valid values: " + VALID_TYPES);
        }
        validateAqlIfPresent(req.config());
        ProjectRule r = new ProjectRule();
        r.setProjectId(projectId);
        r.setRuleType(req.ruleType());
        r.setEnabled(req.enabled() == null || req.enabled());
        r.setNote(req.note());
        r.setConfig(serializeConfig(req.config()));
        r.setCreatedAt(OffsetDateTime.now());
        r.setUpdatedAt(r.getCreatedAt());
        return ProjectRuleDto.from(repo.save(r));
    }

    @Transactional
    public ProjectRuleDto update(Long projectId, Long id, UpdateProjectRuleRequest req) {
        ProjectRule r = repo.findById(id)
            .filter(x -> x.getProjectId().equals(projectId))
            .orElseThrow(() -> NotFoundException.of("project rule", id));
        if (req.enabled() != null) r.setEnabled(req.enabled());
        if (req.note() != null)    r.setNote(req.note());
        if (req.config() != null) {
            validateAqlIfPresent(req.config());
            r.setConfig(serializeConfig(req.config()));
        }
        r.setUpdatedAt(OffsetDateTime.now());
        return ProjectRuleDto.from(repo.save(r));
    }

    @Transactional
    public void delete(Long projectId, Long id) {
        ProjectRule r = repo.findById(id)
            .filter(x -> x.getProjectId().equals(projectId))
            .orElseThrow(() -> NotFoundException.of("project rule", id));
        repo.delete(r);
    }

    /**
     * Upserts required_user_agent / required_header rules from a bug bounty platform's rules
     * of engagement, keyed by (projectId, ruleType, syncedFrom) so re-syncing only ever
     * touches rows this same platform previously created — a manually-created rule of the
     * same type is left completely alone. Passing null for a requirement (platform no longer
     * reports it) removes our previously-synced rule for that type, if any.
     *
     * @param requirements raw values as returned by the platform: "userAgent" -> free text,
     *                      "requestHeader" -> a "Name: value" style string (split below).
     */
    @Transactional
    public void syncPlatformTestingRequirements(Long projectId, String platform, Map<String, String> requirements) {
        String userAgent = requirements.get("userAgent");
        upsertSyncedRule(projectId, platform, "required_user_agent",
            userAgent == null || userAgent.isBlank() ? null : Map.<String, Object>of("userAgent", userAgent),
            "Synced from " + platform + "'s rules of engagement.");

        String requestHeader = requirements.get("requestHeader");
        Map<String, Object> headerConfig = null;
        String headerNote = "Synced from " + platform + "'s rules of engagement.";
        if (requestHeader != null && !requestHeader.isBlank()) {
            int idx = requestHeader.indexOf(':');
            String name  = idx > 0 ? requestHeader.substring(0, idx).trim() : requestHeader.trim();
            String value = idx > 0 ? requestHeader.substring(idx + 1).trim() : "";
            headerConfig = new LinkedHashMap<>();
            headerConfig.put("name", name);
            headerConfig.put("value", value);
            if (value.contains("{") || value.contains("<")) {
                headerNote = "Synced from " + platform + "'s rules of engagement — the value looks like a "
                    + "placeholder (e.g. {Username}); replace it with your actual researcher handle before relying on it.";
            }
        }
        upsertSyncedRule(projectId, platform, "required_header", headerConfig, headerNote);
    }

    private void upsertSyncedRule(Long projectId, String platform, String ruleType,
                                   Map<String, Object> config, String note) {
        var existing = repo.findByProjectIdAndRuleTypeAndSyncedFrom(projectId, ruleType, platform);
        if (config == null) {
            existing.ifPresent(repo::delete);
            return;
        }
        ProjectRule r = existing.orElseGet(() -> {
            ProjectRule created = new ProjectRule();
            created.setProjectId(projectId);
            created.setRuleType(ruleType);
            created.setSyncedFrom(platform);
            created.setEnabled(true);
            created.setCreatedAt(OffsetDateTime.now());
            return created;
        });
        r.setConfig(serializeConfig(config));
        r.setNote(note);
        r.setUpdatedAt(OffsetDateTime.now());
        repo.save(r);
    }

    /** Only "email_recipients" rules carry an optional "aql" condition today — validated eagerly
     *  (rather than left to fail silently at report-send time) so a typo doesn't quietly make a
     *  rule never fire. Every other rule type's config stays entirely client-trusted, unchanged. */
    private void validateAqlIfPresent(Map<String, Object> config) {
        if (config == null) return;
        Object aql = config.get("aql");
        if (!(aql instanceof String s) || s.isBlank()) return;
        try {
            AqlParser.parse(s);
        } catch (AqlParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid aql condition: " + e.getMessage());
        }
    }

    private String serializeConfig(java.util.Map<String, Object> config) {
        if (config == null) return "{}";
        try { return MAPPER.writeValueAsString(config); }
        catch (Exception e) { return "{}"; }
    }
}
