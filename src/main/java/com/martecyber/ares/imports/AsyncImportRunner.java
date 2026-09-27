package com.martecyber.ares.imports;

import com.martecyber.ares.projects.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * The {@code @Async} half of {@link ImportService#startImport} — kept as its own bean (not a
 * method on {@code ImportService} itself) for the same reason {@link ScanImportRecorder} is its
 * own class: {@code @Async} only takes effect when the call comes in through this bean's own
 * Spring proxy, from a DIFFERENT bean — {@code ImportService} calling one of its own methods
 * directly (self-invocation) would silently run it synchronously instead, defeating the whole
 * point. {@code ImportService} depends on this class ({@code @Lazy}, to break the two-way
 * dependency — this class calls back into {@code ImportService#processImport} once dispatched).
 */
@Service
public class AsyncImportRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncImportRunner.class);

    private final ImportService importService;

    public AsyncImportRunner(ImportService importService) {
        this.importService = importService;
    }

    @Async
    public void processAsync(Project project, ScanImport record, ImportParser parser, String toolId,
                              byte[] content, String normalizedSourceIp, String normalizedNacProfile,
                              boolean scopeFilterDisabled, Consumer<ImportResult> onComplete) {
        ImportResult result = importService.processImport(project, record, parser, toolId, content,
            normalizedSourceIp, normalizedNacProfile, scopeFilterDisabled);
        if (onComplete != null) {
            try {
                onComplete.accept(result);
            } catch (Exception e) {
                log.error("Import completion callback failed for scan_import {}: {}", record.getId(), e.getMessage(), e);
            }
        }
    }
}
