package io.github.jaeseongs95.portal.migrate;

import static io.github.jaeseongs95.portal.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Run;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Store;

/**
 * Requirement 6 (defect D-0017): the history may be longer than anything 2.x allowed (its journal stopped at 10,000 rows and 64 MiB), and the migration moves all of it in one
 * run: no row is dropped to make room, no row is refused for the size of the history, and no limit on the number of operations is applied.
 */
class MigrateScaleTest {

    private static final int DENIED = 10_000;
    private static final int COMPLETED = 50;

    private final MigrationKit kit = new MigrationKit();

    @Test
    void moreOperationsThanTheTwoXJournalCouldHoldAreAllMoved(@TempDir Path root) {
        List<LegacyJob> jobs = new ArrayList<>();
        for (int number = 1; number <= DENIED; number++) {
            jobs.add(job(number).state("denied").times(T0, null, T0 + 10).decision("{\"decision\":\"deny\",\"reasonCode\":\"policy_denied\",\"policyRevision\":1}"));
        }
        for (int number = DENIED + 1; number <= DENIED + COMPLETED; number++) {
            jobs.add(job(number).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0)
                .decision("{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}"));
        }
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(jobs);
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("operations to move: " + (DENIED + COMPLETED)).contains("moved now: " + (DENIED + COMPLETED));
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(DENIED + COMPLETED);
            assertThat(store.counts().get(OperationState.DENIED)).isEqualTo(DENIED);
            assertThat(store.counts().get(OperationState.COMPLETED)).isEqualTo(COMPLETED);
        }
    }
}
