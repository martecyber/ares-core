package com.martecyber.ares.imports.dto;

import java.util.List;

public record ScanImportRollbackResult(
    int assetsDeleted,
    int assetsReverted,
    int detectionsDeleted,
    int detectionsReverted,
    List<BlockedItem> blocked
) {
    public record BlockedItem(String entityType, Long entityId, String reason) {}
}
