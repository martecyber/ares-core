package com.martecyber.ares.startup;

import com.martecyber.ares.jobs.Job;
import com.martecyber.ares.jobs.JobRepository;
import com.martecyber.ares.kb.wordlists.KbWordlistRepo;
import com.martecyber.ares.kb.wordlists.KbWordlistRepoRepository;
import com.martecyber.ares.plugins.PluginService;
import com.martecyber.ares.projects.ScopeClassifyScheduler;
import com.martecyber.ares.workflows.LegacyScheduleMigrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * On every startup, marks any jobs that were left in running/pending state
 * (due to server restart or crash) as failed. Also resets any wordlist repo
 * sync statuses that were left in "running" state, and kicks off an async
 * platform-wide third-party reclassification sweep (self-heals any instance
 * where a KB third-party entry was added/edited before that triggered reclassification).
 */
@Component
public class StartupCleanupService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StartupCleanupService.class);

    private final JobRepository jobRepo;
    private final KbWordlistRepoRepository wordlistRepoRepo;
    private final ScopeClassifyScheduler scopeClassifyScheduler;
    private final MarkdownMigrationService markdownMigrationService;
    private final LegacyScheduleMigrationService legacyScheduleMigrationService;
    private final PluginService pluginService;

    public StartupCleanupService(JobRepository jobRepo, KbWordlistRepoRepository wordlistRepoRepo,
                                  ScopeClassifyScheduler scopeClassifyScheduler,
                                  MarkdownMigrationService markdownMigrationService,
                                  LegacyScheduleMigrationService legacyScheduleMigrationService,
                                  PluginService pluginService) {
        this.jobRepo = jobRepo;
        this.wordlistRepoRepo = wordlistRepoRepo;
        this.scopeClassifyScheduler = scopeClassifyScheduler;
        this.markdownMigrationService = markdownMigrationService;
        this.legacyScheduleMigrationService = legacyScheduleMigrationService;
        this.pluginService = pluginService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        cleanupStuckJobs();
        cleanupStuckWordlistRepos();
        markdownMigrationService.migrateLegacyHtmlToMarkdown();
        markdownMigrationService.unescapeBackticks(); // must run after the HTML->Markdown backfill above, since that's what introduces the escaping
        legacyScheduleMigrationService.migrateLegacySchedules(); // Workflows Phase H+ data migration
        scopeClassifyScheduler.scheduleThirdPartyReclassify(); // async, doesn't block startup
        pluginService.loadInstalledPlugins(); // loads every enabled plugin's JAR before any Workflow could reference it
    }

    private void cleanupStuckJobs() {
        List<Job> stuck = jobRepo.findByStatusIn(List.of("running", "pending"));
        if (stuck.isEmpty()) return;

        log.warn("Found {} job(s) stuck in running/pending state — marking as failed (server was restarted)", stuck.size());
        OffsetDateTime now = OffsetDateTime.now();
        for (Job job : stuck) {
            job.setStatus("failed");
            job.setError("Server was restarted while this job was in progress");
            job.setCompletedAt(now);
        }
        jobRepo.saveAll(stuck);
        log.info("Marked {} stuck job(s) as failed", stuck.size());
    }

    private void cleanupStuckWordlistRepos() {
        List<KbWordlistRepo> stuck = wordlistRepoRepo.findAll().stream()
            .filter(r -> "running".equals(r.getLastSyncStatus()))
            .toList();
        if (stuck.isEmpty()) return;

        log.warn("Found {} wordlist repo(s) stuck in running state — marking as failed", stuck.size());
        OffsetDateTime now = OffsetDateTime.now();
        for (KbWordlistRepo repo : stuck) {
            repo.setLastSyncStatus("failed");
            repo.setLastSyncError("Server was restarted while sync was in progress");
            repo.setLastSyncAt(now);
        }
        wordlistRepoRepo.saveAll(stuck);
        log.info("Reset {} stuck wordlist repo sync(s) to failed", stuck.size());
    }
}
