package com.martecyber.ares.projects.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martecyber.ares.aql.compile.AqlInMemoryEvaluator;
import com.martecyber.ares.aql.parser.AqlNode;
import com.martecyber.ares.aql.parser.AqlParser;
import com.martecyber.ares.common.NotFoundException;
import com.martecyber.ares.findings.Finding;
import com.martecyber.ares.findings.FindingAqlRegistry;
import com.martecyber.ares.findings.FindingRepository;
import com.martecyber.ares.organizations.contacts.OrganizationContactRepository;
import com.martecyber.ares.projects.Project;
import com.martecyber.ares.projects.ProjectRepository;
import com.martecyber.ares.users.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves "email_recipients" Rules of Engagement (see {@link ProjectRuleService}, ruleType
 * {@code "email_recipients"}) for one Finding into suggested To/CC/BCC recipients — the
 * auto-fill source for the "Report finding by email" dialog. Purely additive/suggestive: the
 * actual send ({@code FindingEmailReportService.sendEmailForFinding}) still just takes flat
 * address lists, unaware any of this exists.
 */
@Service
public class ReportRecipientRuleService {

    private static final Logger log = LoggerFactory.getLogger(ReportRecipientRuleService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectRuleRepository ruleRepo;
    private final ProjectRepository projectRepo;
    private final OrganizationContactRepository contactRepo;
    private final UserRepository userRepo;
    private final FindingRepository findingRepo;
    private final FindingAqlRegistry findingAqlRegistry;

    public ReportRecipientRuleService(ProjectRuleRepository ruleRepo, ProjectRepository projectRepo,
                                       OrganizationContactRepository contactRepo, UserRepository userRepo,
                                       FindingRepository findingRepo, FindingAqlRegistry findingAqlRegistry) {
        this.ruleRepo = ruleRepo;
        this.projectRepo = projectRepo;
        this.contactRepo = contactRepo;
        this.userRepo = userRepo;
        this.findingRepo = findingRepo;
        this.findingAqlRegistry = findingAqlRegistry;
    }

    public record ResolvedRecipient(String email, String label, Long ruleId, String ruleNote) {}

    public record SuggestedRecipients(
        List<ResolvedRecipient> to, List<ResolvedRecipient> cc, List<ResolvedRecipient> bcc) {}

    public SuggestedRecipients resolve(Long findingId) {
        Finding finding = findingRepo.findById(findingId)
            .orElseThrow(() -> NotFoundException.of("finding", findingId));
        return resolve(finding);
    }

    public SuggestedRecipients resolve(Finding finding) {
        List<ResolvedRecipient> to = new ArrayList<>();
        List<ResolvedRecipient> cc = new ArrayList<>();
        List<ResolvedRecipient> bcc = new ArrayList<>();

        Project project = projectRepo.findById(finding.getProjectId()).orElse(null);
        if (project == null) return new SuggestedRecipients(to, cc, bcc);

        List<ProjectRule> rules = ruleRepo.findByProjectIdAndEnabled(project.getId(), true).stream()
            .filter(r -> "email_recipients".equals(r.getRuleType())).toList();
        if (rules.isEmpty()) return new SuggestedRecipients(to, cc, bcc);

        for (ProjectRule rule : rules) {
            JsonNode config;
            try {
                config = MAPPER.readTree(rule.getConfig() != null ? rule.getConfig() : "{}");
            } catch (Exception e) {
                log.warn("Skipping email_recipients rule {} — unparseable config: {}", rule.getId(), e.getMessage());
                continue;
            }

            String aql = config.path("aql").asText(null);
            if (aql != null && !aql.isBlank() && !matchesAql(finding, aql, rule.getId())) continue;

            // Each recipient carries its OWN placement now (a single rule can put one contact in
            // Cc and another in Bcc) — replaces the earlier one-placement-for-the-whole-rule shape.
            for (JsonNode entry : config.path("recipients")) {
                List<ResolvedRecipient> target = switch (entry.path("placement").asText("to")) {
                    case "cc" -> cc;
                    case "bcc" -> bcc;
                    default -> to;
                };
                String kind = entry.path("kind").asText("");
                long id = entry.path("id").asLong(0);
                if (id <= 0) continue;
                if ("contact".equals(kind)) {
                    contactRepo.findById(id)
                        .filter(c -> c.getOrganizationId().equals(project.getOrganizationId()))
                        .ifPresent(c -> addDeduped(target, c.getEmail(), c.getName(), rule));
                } else if ("user".equals(kind)) {
                    userRepo.findById(id)
                        .ifPresent(u -> addDeduped(target, u.getEmail(), u.getDisplayName(), rule));
                }
            }
        }

        return new SuggestedRecipients(to, cc, bcc);
    }

    private boolean matchesAql(Finding finding, String aql, Long ruleId) {
        try {
            AqlNode node = AqlParser.parse(aql);
            return AqlInMemoryEvaluator.matches(finding, node, findingAqlRegistry);
        } catch (Exception e) {
            // Shouldn't happen — ProjectRuleService validates AQL syntax at save time — but a rule
            // saved before that validation existed, or referencing a field since removed, must not
            // take down recipient resolution for the whole report-send dialog.
            log.warn("email_recipients rule {} has an unevaluable aql condition, skipping it: {}", ruleId, e.getMessage());
            return false;
        }
    }

    private static void addDeduped(List<ResolvedRecipient> target, String email, String label, ProjectRule rule) {
        if (email == null || email.isBlank()) return;
        String normalized = email.trim().toLowerCase();
        boolean exists = target.stream().anyMatch(r -> r.email().equalsIgnoreCase(normalized));
        if (exists) return;
        target.add(new ResolvedRecipient(email.trim(), label, rule.getId(), rule.getNote()));
    }
}
