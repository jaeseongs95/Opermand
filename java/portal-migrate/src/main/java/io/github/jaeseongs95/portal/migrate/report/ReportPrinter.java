package io.github.jaeseongs95.portal.migrate.report;

import java.io.PrintStream;
import java.util.Map;

import io.github.jaeseongs95.portal.migrate.source.InventoryEntry;

/** Prints a {@link MigrationReport} as plain lines, the same words for a dry run and a real run. Nothing printed here is a secret: no command text, no path, no key. */
public final class ReportPrinter {

    private final PrintStream out;

    public ReportPrinter(PrintStream out) {
        this.out = out;
    }

    public void print(MigrationReport report) {
        out.println("portal-migrate: " + (report.mode() == MigrationReport.Mode.DRY_RUN ? "dry run, nothing is written" : "import"));
        out.println("2.x data folder, listed completely:");
        for (InventoryEntry entry : report.inventory()) {
            out.println("  " + entry.classification() + "  " + entry.name() + "  - " + entry.note());
        }
        for (Map.Entry<String, String> fingerprint : report.fingerprints().entrySet()) {
            out.println("source " + fingerprint.getKey() + "  rows read: " + report.rowsBySource().getOrDefault(fingerprint.getKey(), 0) + "  sha256: " + fingerprint.getValue());
        }
        out.println("operations to move: " + report.planned() + " (of them unknown: " + report.plannedUnknown() + "), covered duplicates of another file: "
            + report.coveredDuplicates());
        for (Map.Entry<String, Integer> skipped : report.notMigrated().entrySet()) {
            out.println("  not moved: " + skipped.getValue() + " x " + skipped.getKey());
        }
        for (String text : report.reinterpretations()) {
            out.println("  stored differently from 2.x: " + text);
        }
        if (report.unknownBudget() > 0) {
            out.println("target store: already moved " + report.alreadyImported() + ", unknown operations in it " + report.existingUnknown() + ", unknown budget "
                + report.unknownBudget());
        }
        if (report.mode() == MigrationReport.Mode.IMPORT && report.outcome() == MigrationReport.Outcome.SUCCESS) {
            out.println("moved now: " + report.imported() + " (of them unknown: " + report.importedUnknown() + ")");
        }
        for (MigrationReport.Issue issue : report.issues()) {
            out.println("CONFLICT " + issue.code() + "  " + issue.reference() + "  " + issue.message());
        }
        if (report.errorMessage() != null) {
            out.println("ERROR " + report.errorMessage());
        }
        out.println("result: " + report.outcome() + " (exit code " + report.outcome().exitCode() + ")"
            + (report.outcome() == MigrationReport.Outcome.CONFLICT ? "; nothing was written to the 3.0 store" : ""));
        out.flush();
    }
}
