package io.github.jaeseongs95.portal.migrate.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.portal.migrate.source.InventoryEntry;

/**
 * What one run found and did, collected while it runs and printed at its end ({@link ReportPrinter}). A conflict names the 2.x row and, when the 3.0 store is involved, the
 * 3.0 operation it collides with; nothing is hidden behind an exception. The outcome fixes the exit code: 0 for success, 1 for an error (the run could not do its work),
 * 2 for a conflict (the data cannot be moved as it is; nothing was written).
 */
public final class MigrationReport {

    /** Whether the run may write. */
    public enum Mode {
        DRY_RUN, IMPORT
    }

    /** How the run ended. */
    public enum Outcome {
        SUCCESS(0), ERROR(1), CONFLICT(2);

        private final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    /** A thing that stops the migration: the code names the kind, the reference the 2.x row (or the run), the message says what and why. */
    public record Issue(String code, String reference, String message) {
    }

    private final Mode mode;
    private final List<InventoryEntry> inventory = new ArrayList<>();
    private final Map<String, String> fingerprints = new LinkedHashMap<>();
    private final Map<String, Integer> rowsBySource = new LinkedHashMap<>();
    private final Map<String, Integer> notMigrated = new LinkedHashMap<>();
    private final List<Issue> issues = new ArrayList<>();
    private final List<String> reinterpretations = new ArrayList<>();
    private int planned;
    private int plannedUnknown;
    private int coveredDuplicates;
    private int alreadyImported;
    private int existingUnknown;
    private int unknownBudget;
    private int imported;
    private int importedUnknown;
    private String errorMessage;

    public MigrationReport(Mode mode) {
        this.mode = mode;
    }

    public Mode mode() {
        return mode;
    }

    public void inventory(List<InventoryEntry> entries) {
        inventory.addAll(entries);
    }

    public void fingerprint(String file, String sha256) {
        fingerprints.put(file, sha256);
    }

    public void rowRead(String source) {
        rowsBySource.merge(source, 1, Integer::sum);
    }

    public void notMigrated(String source, String kind, String reason) {
        notMigrated.merge(source + ": " + kind + " operations not moved: " + reason, 1, Integer::sum);
    }

    public void issue(String code, String reference, String message) {
        issues.add(new Issue(code, reference, message));
    }

    public void reinterpreted(String reference, String text) {
        reinterpretations.add(reference + ": " + text);
    }

    public void planned(boolean unknown) {
        planned++;
        if (unknown) {
            plannedUnknown++;
        }
    }

    public void coveredDuplicate() {
        coveredDuplicates++;
    }

    public void targetFound(int alreadyImportedCount, int existingUnknownCount, int budget) {
        alreadyImported = alreadyImportedCount;
        existingUnknown = existingUnknownCount;
        unknownBudget = budget;
    }

    public void imported(int count, int unknownCount) {
        imported = count;
        importedUnknown = unknownCount;
    }

    public void error(String message) {
        errorMessage = message;
    }

    /** The outcome: an error wins over a conflict, a conflict over success. */
    public Outcome outcome() {
        if (errorMessage != null) {
            return Outcome.ERROR;
        }
        return issues.isEmpty() ? Outcome.SUCCESS : Outcome.CONFLICT;
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }

    public List<InventoryEntry> inventory() {
        return List.copyOf(inventory);
    }

    public Map<String, String> fingerprints() {
        return Collections.unmodifiableMap(fingerprints);
    }

    public Map<String, Integer> rowsBySource() {
        return Collections.unmodifiableMap(rowsBySource);
    }

    public Map<String, Integer> notMigrated() {
        return Collections.unmodifiableMap(notMigrated);
    }

    public List<Issue> issues() {
        return List.copyOf(issues);
    }

    public List<String> reinterpretations() {
        return List.copyOf(reinterpretations);
    }

    public int planned() {
        return planned;
    }

    public int plannedUnknown() {
        return plannedUnknown;
    }

    public int coveredDuplicates() {
        return coveredDuplicates;
    }

    public int alreadyImported() {
        return alreadyImported;
    }

    public int existingUnknown() {
        return existingUnknown;
    }

    public int unknownBudget() {
        return unknownBudget;
    }

    public int imported() {
        return imported;
    }

    public int importedUnknown() {
        return importedUnknown;
    }

    public String errorMessage() {
        return errorMessage;
    }
}
