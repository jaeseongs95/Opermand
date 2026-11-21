package io.github.jaeseongs95.portal.migrate.run;

import java.util.HashSet;
import java.util.Set;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.migrate.plan.ImportPlan;
import io.github.jaeseongs95.portal.migrate.plan.Translation;
import io.github.jaeseongs95.portal.migrate.report.MigrationReport;
import io.github.jaeseongs95.portal.migrate.source.LegacySource;
import io.github.jaeseongs95.portal.migrate.target.ExistingHistory;
import io.github.jaeseongs95.portal.migrate.target.HistoryWriter;
import io.github.jaeseongs95.portal.migrate.target.TargetSchema;

/**
 * The second half of a real run: the schema, the checks against the store, and then every write in one transaction. The transaction is the whole guarantee of "all or
 * nothing": any failure while writing, a store constraint or a 2.x file that changed while it was read, rolls back every row of this run (a previous run's rows stay: they were
 * committed by that run). The 2.x files are read a second time for the writes, row by row, so that a long history is never held in memory, and their fingerprints are compared
 * before the transaction commits.
 */
public final class ImportRunner implements ApplicationRunner {

    private final MigrationInput input;
    private final TargetSchema schema;
    private final ExistingHistory existing;
    private final HistoryWriter writer;
    private final PlatformTransactionManager transactions;

    public ImportRunner(MigrationInput input, TargetSchema schema, ExistingHistory existing, HistoryWriter writer, PlatformTransactionManager transactions) {
        this.input = input;
        this.schema = schema;
        this.existing = existing;
        this.writer = writer;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        MigrationReport report = input.report();
        try {
            schema.ensureCurrent();
            Set<String> alreadyMoved = new TargetCheck(existing, report).check(input);
            if (report.hasIssues()) {
                return;
            }
            Tally tally = new Tally();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                writeAll(alreadyMoved, tally);
                requireSourcesUnchanged();
            });
            report.imported(tally.written, tally.unknown);
        } catch (RuntimeException e) {
            report.error("the migration failed and every write of this run was rolled back: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void writeAll(Set<String> alreadyMoved, Tally tally) {
        Set<String> seen = new HashSet<>();
        for (LegacySource source : input.sources()) {
            source.forEachRow(row -> {
                if (input.translator().translate(row) instanceof Translation.Import moved) {
                    ImportPlan plan = moved.plan();
                    if (seen.add(plan.id().value()) && !alreadyMoved.contains(plan.id().value())) {
                        writer.write(plan);
                        tally.add(plan);
                    }
                }
            });
        }
    }

    private void requireSourcesUnchanged() {
        if (!input.directory().fingerprints().equals(input.fingerprints())) {
            throw new IllegalStateException("a 2.x journal changed while the migration read it (is the 2.x service running?)");
        }
    }

    /** The count of what this run wrote. */
    private static final class Tally {

        private int written;
        private int unknown;

        void add(ImportPlan plan) {
            written++;
            if (plan.finalState() == OperationState.UNKNOWN) {
                unknown++;
            }
        }
    }
}
