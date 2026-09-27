package com.martecyber.ares.imports;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Commits a new {@code ScanImport} row in its own, immediately-committed transaction —
 * separate from {@code ImportService}'s own class so the REQUIRES_NEW annotation actually
 * goes through a Spring proxy (a same-class self-invocation would silently ignore it).
 * This has to happen before any asset resolution starts: {@code AssetImportHelper}'s asset
 * methods run in their own REQUIRES_NEW sub-transactions, which — being separate DB
 * transactions — can only see already-committed rows, so a scan_import_change insert
 * referencing this scan_import's id would otherwise fail its FK check.
 */
@Service
public class ScanImportRecorder {

    private final ScanImportRepository repo;

    public ScanImportRecorder(ScanImportRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScanImport commit(ScanImport draft) {
        return repo.save(draft);
    }
}
