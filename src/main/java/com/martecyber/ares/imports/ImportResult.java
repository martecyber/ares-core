package com.martecyber.ares.imports;

import java.util.ArrayList;
import java.util.List;

public class ImportResult {

    private boolean success;
    private String message;
    private int assetsCreated;
    private int detectionsCreated;
    private int detectionsUpdated;
    private int visibilityRecorded;
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public ImportResult() {}

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public int getAssetsCreated() { return assetsCreated; }
    public void setAssetsCreated(int assetsCreated) { this.assetsCreated = assetsCreated; }

    public int getDetectionsCreated() { return detectionsCreated; }
    public void setDetectionsCreated(int detectionsCreated) { this.detectionsCreated = detectionsCreated; }

    public int getDetectionsUpdated() { return detectionsUpdated; }
    public void setDetectionsUpdated(int detectionsUpdated) { this.detectionsUpdated = detectionsUpdated; }

    public int getVisibilityRecorded() { return visibilityRecorded; }
    public void setVisibilityRecorded(int visibilityRecorded) { this.visibilityRecorded = visibilityRecorded; }

    public List<String> getErrors() { return errors; }
    public void addError(String error) { this.errors.add(error); }

    public List<String> getWarnings() { return warnings; }
    public void addWarning(String warning) { this.warnings.add(warning); }
}
