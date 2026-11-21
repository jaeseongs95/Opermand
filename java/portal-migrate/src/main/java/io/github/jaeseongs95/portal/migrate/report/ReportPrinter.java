package io.github.jaeseongs95.portal.migrate.report;

import java.io.PrintStream;
import java.util.Map;

import io.github.jaeseongs95.portal.migrate.source.InventoryEntry;

/**
 * Prints a {@link MigrationReport} as plain lines, the same words for a dry run and a real run. Nothing printed here is a secret: no command text, no path, no key, no setting
 * value (the settings are named by key). A line ends with LF on every system ({@code println} would end it with the line separator of the system the command runs on, so the
 * same report would differ between Linux and Windows).
 */
public final class ReportPrinter {

    private static final char LINE_END = '\n';

    private final PrintStream out;

    public ReportPrinter(PrintStream out) {
        this.out = out;
    }

    /** Writes one line, ended by LF. */
    public static void line(PrintStream out, String text) {
        out.print(text + LINE_END);
    }

    public void print(MigrationReport report) {
        line("portal-migrate: " + (report.mode() == MigrationReport.Mode.DRY_RUN ? "dry run, nothing is written" : "import"));
        line("2.x data folder, listed completely:");
        for (InventoryEntry entry : report.inventory()) {
            line("  " + entry.classification() + "  " + entry.name() + "  - " + entry.note());
        }
        for (Map.Entry<String, String> fingerprint : report.fingerprints().entrySet()) {
            line("source " + fingerprint.getKey() + "  rows read: " + report.rowsBySource().getOrDefault(fingerprint.getKey(), 0) + "  sha256: " + fingerprint.getValue());
        }
        line("operations to move: " + report.planned() + " (of them unknown: " + report.plannedUnknown() + "), covered duplicates of another file: " + report.coveredDuplicates());
        for (Map.Entry<String, Integer> skipped : report.notMigrated().entrySet()) {
            line("  not moved: " + skipped.getValue() + " x " + skipped.getKey());
        }
        for (String text : report.reinterpretations()) {
            line("  stored differently from 2.x: " + text);
        }
        if (report.unknownBudget() > 0) {
            line("target store: already moved " + report.alreadyImported() + ", unknown operations in it " + report.existingUnknown() + ", unknown budget " + report.unknownBudget());
        }
        if (report.mode() == MigrationReport.Mode.IMPORT && report.outcome() == MigrationReport.Outcome.SUCCESS && !report.fingerprints().isEmpty()) {
            line("moved now: " + report.imported() + " (of them unknown: " + report.importedUnknown() + ")");
        }
        printSettings(report);
        for (MigrationReport.Issue issue : report.issues()) {
            line("CONFLICT " + issue.code() + "  " + issue.reference() + "  " + issue.message());
        }
        if (report.errorMessage() != null) {
            line("ERROR " + report.errorMessage());
        }
        line("result: " + report.outcome() + " (exit code " + report.outcome().exitCode() + ")"
            + (report.outcome() == MigrationReport.Outcome.CONFLICT ? "; nothing was written to the 3.0 store" : ""));
        out.flush();
    }

    private void printSettings(MigrationReport report) {
        if (report.settingsNotes().isEmpty() && report.settingsResult() == null) {
            return;
        }
        line("settings of the 2.x environment file:");
        for (String note : report.settingsNotes()) {
            line("  " + note);
        }
        if (report.settingsResult() != null) {
            line("  " + report.settingsResult());
        }
        for (String action : report.actions()) {
            line("ACTION REQUIRED " + action);
        }
    }

    private void line(String text) {
        line(out, text);
    }
}
