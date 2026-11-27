package io.github.jaeseongs95.opermand.migrate;

import static io.github.jaeseongs95.opermand.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.opermand.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.opermand.domain.operation.OperationEvent;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Run;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Store;

/**
 * Requirement 3 (defects D-0039, D-0018): a collision is reported with the 2.x row and the 3.0 operation it collides with, never hidden in an exception, no key is changed to
 * avoid it, and nothing of the run stays in the 3.0 store.
 */
class MigrateConflictTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";

    private final MigrationKit kit = new MigrationKit();

    private static String id(int number) {
        return String.format("%048x", number);
    }

    private static LegacyJob done(int number) {
        return job(number).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE);
    }

    @Test
    void aCollisionInTheMiddleLeavesNothingOfTheRunInTheStoreAndNamesBothRows(@TempDir Path root) {
        Path target = root.resolve("v3");
        LegacyFixture first = new LegacyFixture(root.resolve("legacy-1")).operations(List.of(done(1), done(2)));
        assertThat(kit.run(kit.importArgs(first.folder(), target)).exitCode()).isZero();
        Map<OperationState, Integer> countsBefore;
        List<OperationEvent> eventsOfTwoBefore;
        try (Store store = kit.open(target)) {
            countsBefore = store.counts();
            eventsOfTwoBefore = store.events(id(2));
        }
        // The second folder has a new operation, then an operation that has the request key of operation 2 but is another operation (another id and command), then a new one.
        LegacyFixture second = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(
            done(3), done(4).key("key-" + id(2)).command("rm -rf /tmp/something-else"), done(5)));
        Map<String, String> sourceBefore = kit.fingerprint(second.folder());

        Run run = kit.run(kit.importArgs(second.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT REQUEST_KEY_IN_TARGET  operations.sqlite#" + id(4))
            .contains("request key key-" + id(2) + " of operation " + id(4) + " is already used by operation " + id(2) + " in the 3.0 store")
            .contains("nothing was written to the 3.0 store");
        try (Store store = kit.open(target)) {
            assertThat(store.has(id(3))).as("the row before the collision was not written").isFalse();
            assertThat(store.has(id(5))).as("the row after the collision was not written").isFalse();
            assertThat(store.counts()).isEqualTo(countsBefore);
            assertThat(store.events(id(2))).isEqualTo(eventsOfTwoBefore);
            assertThat(store.commandOf(id(2))).as("the existing operation is as it was").isEqualTo("echo hello");
        }
        assertThat(kit.fingerprint(second.folder())).isEqualTo(sourceBefore);
    }

    @Test
    void anIdThatTheStoreHoldsUnderAnotherKeyIsAConflictToo(@TempDir Path root) {
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(new LegacyFixture(root.resolve("legacy-1")).operations(List.of(done(1))).folder(), target)).exitCode()).isZero();
        LegacyFixture second = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(1).key("another-key-for-operation-1")));

        Run run = kit.run(kit.importArgs(second.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT ID_IN_TARGET  operations.sqlite#" + id(1));
    }

    @Test
    void theSameOperationWithAnotherContractUnderTheSameKeyIsAConflict(@TempDir Path root) {
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(new LegacyFixture(root.resolve("legacy-1")).operations(List.of(done(1))).folder(), target)).exitCode()).isZero();
        LegacyFixture second = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(1).command("echo a different command")));

        Run run = kit.run(kit.importArgs(second.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("the same id with another contract");
        try (Store store = kit.open(target)) {
            assertThat(store.commandOf(id(1))).isEqualTo("echo hello");
        }
    }

    @Test
    void aRequestKeyTwoOperationsShareIsReportedBeforeAnyStoreIsCreated(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"))
            .operations(List.of(done(1).key("shared-key-0000000000000001")))
            .commandJournal(List.of(done(2).key("shared-key-0000000000000001")));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT REQUEST_KEY_SHARED  command-automation.sqlite#" + id(2))
            .contains("belongs to operation " + id(2) + " here and to operation " + id(1) + " in operations.sqlite#" + id(1));
        assertThat(target).as("a folder that cannot be moved creates no store").doesNotExist();
    }

    @Test
    void anIdWithTwoKeysInTwoFilesIsReported(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"))
            .operations(List.of(done(1).key("key-one-0000000000000001")))
            .commandJournal(List.of(done(1).key("key-two-0000000000000001")));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT ID_WITH_TWO_KEYS  command-automation.sqlite#" + id(1)).contains("keys are never changed");
        assertThat(target).doesNotExist();
    }

    @Test
    void aRequestKeyThatIsNotA3KeyIsReportedAndNeverRewritten(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1).key("short"), done(2)));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT UNREPRESENTABLE_ROW  operations.sqlite#" + id(1)).contains("keys are never rewritten");
        assertThat(target).doesNotExist();
    }

    @Test
    void aCommandWithAHiddenCharacterIsReportedAndNeverCleaned(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1).command("echo bell\u0007"), done(2)));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT UNREPRESENTABLE_ROW  operations.sqlite#" + id(1)).contains("hidden or control character");
        assertThat(target).doesNotExist();
    }

    @Test
    void aJournalWithAnotherSchemaStopsTheRunWithAnError(@TempDir Path root) throws Exception {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1)));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + legacy.folder().resolve("operations.sqlite").toString().replace('\\', '/'));
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("UPDATE operation_schema SET version = 2");
        }
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("this tool reads version 1 only");
        assertThat(target).doesNotExist();
    }
}
