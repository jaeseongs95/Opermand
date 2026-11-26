package io.github.jaeseongs95.portal.migrate;

import static io.github.jaeseongs95.portal.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.domain.operation.OperationEvent;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Run;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Store;

/** Requirement 4 (rerun, defect D-0016) and the dry run of requirement 9; and the 2.x files are never changed by any of them. */
class MigrateRerunAndDryRunTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";

    private final MigrationKit kit = new MigrationKit();

    private static String id(int number) {
        return String.format("%048x", number);
    }

    private static LegacyJob done(int number) {
        return job(number).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE);
    }

    @Test
    void theSameFolderRunAgainMovesNothingAndTouchesNothing(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1), done(2), job(3).state("running").times(T0, T0 + 5, null).decision(EXECUTE)));
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(legacy.folder(), target)).exitCode()).isZero();
        Map<OperationState, Integer> countsBefore;
        List<OperationEvent> eventsBefore;
        try (Store store = kit.open(target)) {
            countsBefore = store.counts();
            eventsBefore = store.events(id(1));
        }

        Run again = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(again.exitCode()).as(again.output()).isZero();
        assertThat(again.output()).contains("already moved 3").contains("moved now: 0");
        try (Store store = kit.open(target)) {
            assertThat(store.counts()).as("no operation was moved twice").isEqualTo(countsBefore);
            assertThat(store.events(id(1))).as("no transition was added to a moved operation").isEqualTo(eventsBefore);
        }
    }

    @Test
    void aLaterRunWithMoreHistoryMovesOnlyTheNewOperations(@TempDir Path root) {
        Path target = root.resolve("v3");
        LegacyFixture first = new LegacyFixture(root.resolve("legacy-1")).operations(List.of(done(1), done(2), done(3)));
        assertThat(kit.run(kit.importArgs(first.folder(), target)).exitCode()).isZero();
        LegacyFixture later = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(1), done(2), done(3), done(4), done(5)));

        Run run = kit.run(kit.importArgs(later.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("already moved 3").contains("moved now: 2");
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(5);
        }
    }

    @Test
    void aDryRunWritesNothingAndDoesNotEvenCreateAStore(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1), done(2), job(3).state("running").times(T0, T0 + 5, null).decision(EXECUTE)))
            .otherFiles();
        Map<String, String> sourceBefore = kit.fingerprint(legacy.folder());
        Path target = root.resolve("v3");

        Run run = kit.run(kit.dryRunArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("dry run, nothing is written").contains("operations to move: 3 (of them unknown: 1)").contains("result: SUCCESS");
        assertThat(target).as("the dry run made no folder and no store").doesNotExist();
        assertThat(kit.fingerprint(legacy.folder())).isEqualTo(sourceBefore);
    }

    @Test
    void aDryRunAgainstAnExistingStoreChangesNoByteOfIt(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1), done(2)));
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(legacy.folder(), target)).exitCode()).isZero();
        LegacyFixture more = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(1), done(2), done(3)));
        Map<String, String> storeBefore = kit.fingerprint(target);
        Map<String, String> sourceBefore = kit.fingerprint(more.folder());

        Run run = kit.run(kit.dryRunArgs(more.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("already moved 2").contains("operations to move: 3");
        assertThat(kit.fingerprint(target)).as("the store folder is byte for byte what it was").isEqualTo(storeBefore);
        assertThat(kit.fingerprint(more.folder())).isEqualTo(sourceBefore);
        try (Store store = kit.open(target)) {
            assertThat(store.total()).as("the dry run moved nothing").isEqualTo(2);
        }
    }

    @Test
    void aDryRunReportsAConflictAndExitsWithTwo(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1)));
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(legacy.folder(), target)).exitCode()).isZero();
        LegacyFixture clash = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(2).key("key-" + id(1))));
        Map<String, String> storeBefore = kit.fingerprint(target);

        Run run = kit.run(kit.dryRunArgs(clash.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isEqualTo(2);
        assertThat(run.output()).contains("CONFLICT REQUEST_KEY_IN_TARGET");
        assertThat(kit.fingerprint(target)).isEqualTo(storeBefore);
    }

    @Test
    void aRealRunLeavesTheTwoXFilesByteForByteAndWithoutAJournalFile(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1))).commandJournal(List.of(done(2))).agentCliJournal(List.of(done(3)
            .key("agent-cli:0123456789abcdef0123456789abcdef"))).otherFiles();
        Map<String, String> sourceBefore = kit.fingerprint(legacy.folder());

        Run run = kit.run(kit.importArgs(legacy.folder(), root.resolve("v3")));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(kit.fingerprint(legacy.folder())).as("same files, same bytes").isEqualTo(sourceBefore);
        assertThat(run.output()).contains("sha256: " + sourceBefore.get("operations.sqlite"));
    }

    @Test
    void aDryRunChecksMetadataOnlyAndNeverCreatesOrReadsAKeyring(@TempDir Path root) throws IOException {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(done(1), done(2)));
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(legacy.folder(), target)).exitCode()).isZero();
        Path keyringDir = target.resolve("keyring");
        Path keyFile = keyringDir.resolve("attachment.keys");
        assertThat(keyFile).as("real import creates the attachment keyring").isRegularFile();

        // Remove the keyring completely; a metadata-only dry-run must neither need nor recreate it.
        Files.delete(keyFile);
        Files.delete(keyringDir);
        assertThat(keyringDir).doesNotExist();

        LegacyFixture more = new LegacyFixture(root.resolve("legacy-2")).operations(List.of(done(1), done(2), done(3)));
        Run dryOk = kit.run(kit.dryRunArgs(more.folder(), target));
        assertThat(dryOk.exitCode()).as(dryOk.output()).isZero();
        assertThat(dryOk.output()).contains("already moved 2").contains("operations to move: 3");
        assertThat(keyringDir).as("dry-run must never create a keyring folder or file").doesNotExist();

        LegacyFixture clash = new LegacyFixture(root.resolve("legacy-3")).operations(List.of(done(1).command("echo tampered")));
        Run dryConflict = kit.run(kit.dryRunArgs(clash.folder(), target));
        assertThat(dryConflict.exitCode()).as(dryConflict.output()).isEqualTo(2);
        assertThat(dryConflict.output()).contains("CONFLICT REQUEST_KEY_IN_TARGET");
        assertThat(keyringDir).as("conflicting dry-run must never create a keyring folder or file").doesNotExist();
    }
}
