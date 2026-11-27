package io.github.jaeseongs95.opermand.migrate.run;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import io.github.jaeseongs95.opermand.migrate.report.MigrationReport;
import io.github.jaeseongs95.opermand.migrate.target.ExistingHistory;

/**
 * The second half of a dry run: the checks against the 3.0 store that already exists, read only. The context of a dry run has no writer and applies no script, so nothing
 * here can write; a store that cannot be read (it is behind, or not a 3.0 store) is an error, not a guess.
 */
public final class DryRunRunner implements ApplicationRunner {

    private final MigrationInput input;
    private final ExistingHistory existing;

    public DryRunRunner(MigrationInput input, ExistingHistory existing) {
        this.input = input;
        this.existing = existing;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        MigrationReport report = input.report();
        try {
            new TargetCheck(existing, report).check(input);
        } catch (RuntimeException e) {
            report.error("the 3.0 store could not be read for the dry run: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
