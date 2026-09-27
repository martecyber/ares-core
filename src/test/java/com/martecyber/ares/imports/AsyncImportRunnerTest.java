package com.martecyber.ares.imports;

import com.martecyber.ares.projects.Project;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Unit coverage for {@link AsyncImportRunner} itself — {@link ImportServiceTest} and {@code
 *  AgentTaskServiceTest} both mock this class away, so neither exercises its actual body: call
 *  {@code ImportService#processImport} and, whatever it returns, hand it to the caller-supplied
 *  callback. Doesn't exercise the {@code @Async} dispatch itself (that's Spring's own, already
 *  well-tested machinery) — just this class's own logic. */
class AsyncImportRunnerTest {

    @Test
    void processAsyncCallsProcessImportAndForwardsTheResultToTheCallback() {
        ImportService importService = mock(ImportService.class);
        AsyncImportRunner runner = new AsyncImportRunner(importService);

        Project project = new Project();
        ScanImport record = new ScanImport();
        ImportParser parser = mock(ImportParser.class);
        ImportResult expected = new ImportResult();
        expected.setSuccess(true);
        expected.setMessage("done");
        when(importService.processImport(project, record, parser, "nmap",
            "content".getBytes(), "1.2.3.4", "profile", true)).thenReturn(expected);

        AtomicReference<ImportResult> received = new AtomicReference<>();
        runner.processAsync(project, record, parser, "nmap", "content".getBytes(),
            "1.2.3.4", "profile", true, received::set);

        assertSame(expected, received.get());
    }

    @Test
    void processAsyncToleratesANullCallback() {
        ImportService importService = mock(ImportService.class);
        AsyncImportRunner runner = new AsyncImportRunner(importService);
        Project project = new Project();
        ScanImport record = new ScanImport();
        ImportParser parser = mock(ImportParser.class);
        when(importService.processImport(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new ImportResult());

        assertDoesNotThrow(() -> runner.processAsync(project, record, parser, "nmap",
            "x".getBytes(), null, null, false, null));
    }

    @Test
    void processAsyncSwallowsAnExceptionThrownByTheCallbackItself() {
        // A broken callback (e.g. AgentTaskService.finishAfterImport hitting an unexpected
        // error) must not propagate back into the @Async-dispatched thread uncaught — there's
        // no one there to catch it, and Spring's default @Async exception handler would only
        // log it anyway. Logging it here is the whole point.
        ImportService importService = mock(ImportService.class);
        AsyncImportRunner runner = new AsyncImportRunner(importService);
        Project project = new Project();
        ScanImport record = new ScanImport();
        ImportParser parser = mock(ImportParser.class);
        when(importService.processImport(any(), any(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new ImportResult());

        assertDoesNotThrow(() -> runner.processAsync(project, record, parser, "nmap",
            "x".getBytes(), null, null, false, ir -> { throw new RuntimeException("callback exploded"); }));
    }
}
