package com.martecyber.ares.startup;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triggers for one-time/idempotent maintenance tasks that normally only run
 * automatically on boot (see {@link StartupCleanupService}). Useful to force a
 * re-run without restarting the whole app — e.g. to verify the Markdown migration
 * actually executed, or to re-run it after fixing a data edge case it missed.
 */
@RestController
@RequestMapping("/api/v1/maintenance")
public class MaintenanceController {

    private final MarkdownMigrationService markdownMigrationService;

    public MaintenanceController(MarkdownMigrationService markdownMigrationService) {
        this.markdownMigrationService = markdownMigrationService;
    }

    /** Combined result of both maintenance passes, run in the same order as boot-time
     * ({@link StartupCleanupService}): HTML->Markdown backfill, then backtick unescape. */
    public record MigrateMarkdownResult(MarkdownMigrationService.MigrationResult htmlToMarkdown,
                                         MarkdownMigrationService.BacktickUnescapeResult backtickUnescape) {
    }

    @PostMapping("/migrate-markdown")
    @PreAuthorize("hasRole('MSSP_ADMIN')")
    public MigrateMarkdownResult migrateMarkdown() {
        MarkdownMigrationService.MigrationResult htmlToMarkdown = markdownMigrationService.migrateLegacyHtmlToMarkdown();
        MarkdownMigrationService.BacktickUnescapeResult backtickUnescape = markdownMigrationService.unescapeBackticks();
        return new MigrateMarkdownResult(htmlToMarkdown, backtickUnescape);
    }
}
