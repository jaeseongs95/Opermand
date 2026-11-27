package io.github.jaeseongs95.opermand.migrate.run;

import java.util.List;
import java.util.Map;

import io.github.jaeseongs95.opermand.migrate.plan.LegacyTranslator;
import io.github.jaeseongs95.opermand.migrate.report.MigrationReport;
import io.github.jaeseongs95.opermand.migrate.source.LegacyDirectory;
import io.github.jaeseongs95.opermand.migrate.source.LegacySource;

/**
 * What the second half of a run (the part that talks to the 3.0 store, inside the store's Spring context) receives from the first half (the 2.x data, read and checked
 * without any store): the sources to read again, the translator, the planned rows, the fingerprints taken before the first read, the unknown budget the 3.0 server will run
 * with, and the report to fill.
 *
 * @param directory the 2.x folder
 * @param sources the journals that are moved
 * @param translator turns a row into a plan
 * @param planned the rows that are to be moved, in the order they are written
 * @param fingerprints the SHA-256 of each journal before it was read
 * @param unknownBudget the unknown budget of the 3.0 server (the scheduler starts nothing new once this many operations are unknown)
 * @param report the report of the run
 */
public record MigrationInput(LegacyDirectory directory, List<LegacySource> sources, LegacyTranslator translator, List<PlannedRow> planned, Map<String, String> fingerprints,
                             int unknownBudget, MigrationReport report) {
}
