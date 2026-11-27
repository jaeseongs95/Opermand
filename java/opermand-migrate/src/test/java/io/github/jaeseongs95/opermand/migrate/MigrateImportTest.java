package io.github.jaeseongs95.opermand.migrate;

import static io.github.jaeseongs95.opermand.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.opermand.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.opermand.domain.operation.ApprovalMode;
import io.github.jaeseongs95.opermand.domain.operation.DecisionReason;
import io.github.jaeseongs95.opermand.domain.operation.DecisionRecord;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationEvent;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.OutcomeCertainty;
import io.github.jaeseongs95.opermand.domain.operation.Reconciliation;
import io.github.jaeseongs95.opermand.domain.operation.RecoveryReason;
import io.github.jaeseongs95.opermand.domain.operation.Settlement;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Run;
import io.github.jaeseongs95.opermand.migrate.MigrationKit.Store;

/**
 * Requirements 1 and 2 (the scope) and 7 (unknown operations): a 2.x folder made by the test is moved, and the 3.0 store is read back through the store's own journal and
 * compared with values the test chose. Every test makes its own folders and shares nothing with another.
 */
class MigrateImportTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":4}";
    private static final String DENY = "{\"decision\":\"deny\",\"reasonCode\":\"policy_denied\",\"policyRevision\":4}";

    private final MigrationKit kit = new MigrationKit();

    private static String id(int number) {
        return String.format("%048x", number);
    }

    @Test
    void everyStateIsMovedWithItsCommandItsDecisionAndItsTimes(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE).command("systemctl status 한글-서비스 😀"),
            job(2).state("failed").times(T0, T0 + 1000, T0 + 2000).exited(2).decision(EXECUTE),
            job(3).state("cancelled").times(T0, null, T0 + 500),
            job(4).state("denied").times(T0, null, T0 + 10).decision(DENY),
            job(5).state("expired").times(T0, null, T0 + 3_600_000L),
            job(6).state("pending"),
            job(7).state("unknown").times(T0, T0 + 1000, T0 + 2000).transportLost().decision(EXECUTE),
            job(8).state("running").times(T0, T0 + 100, null).decision(EXECUTE),
            job(9).state("queued").decision(EXECUTE))).otherFiles();
        Map<String, String> sourceBefore = kit.fingerprint(legacy.folder());
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("operations to move: 9 (of them unknown: 3)").contains("moved now: 9 (of them unknown: 3)");
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(9);
            assertThat(store.byId(id(1)).state()).isEqualTo(OperationState.COMPLETED);
            assertThat(store.commandOf(id(1))).isEqualTo("systemctl status 한글-서비스 😀");
            assertThat(store.byId(id(2)).state()).isEqualTo(OperationState.FAILED);
            assertThat(store.byId(id(3)).state()).isEqualTo(OperationState.CANCELLED);
            assertThat(store.byId(id(4)).state()).isEqualTo(OperationState.DENIED);
            assertThat(store.byId(id(5)).state()).isEqualTo(OperationState.EXPIRED);
            assertThat(store.byId(id(6)).state()).as("2.x recovery turned waiting requests into expired").isEqualTo(OperationState.EXPIRED);
            assertThat(store.byId(id(7)).state()).isEqualTo(OperationState.UNKNOWN);
            assertThat(store.byId(id(8)).state()).as("in flight when 2.x stopped").isEqualTo(OperationState.UNKNOWN);
            assertThat(store.byId(id(9)).state()).as("queued when 2.x stopped: may have been dispatched").isEqualTo(OperationState.UNKNOWN);

            Operation completed = store.byId(id(1));
            assertThat(completed.requestKey().value()).isEqualTo("key-" + id(1));
            assertThat(completed.approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
            assertThat(completed.createdAt()).isEqualTo(Instant.ofEpochMilli(T0));
            assertThat(completed.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> {
                assertThat(settled.finishedAt()).isEqualTo(Instant.ofEpochMilli(T0 + 2000));
                assertThat(settled.certainty()).isEqualTo(OutcomeCertainty.EXITED);
            });
            assertThat(store.events(id(1))).extracting(OperationEvent::state)
                .containsExactly(OperationState.QUEUED, OperationState.STARTING, OperationState.RUNNING, OperationState.COMPLETED);
            assertThat(store.events(id(1))).extracting(OperationEvent::occurredAt)
                .containsExactly(Instant.ofEpochMilli(T0), Instant.ofEpochMilli(T0 + 1000), Instant.ofEpochMilli(T0 + 1000), Instant.ofEpochMilli(T0 + 2000));

            assertThat(store.byId(id(4)).decision()).isInstanceOfSatisfying(DecisionRecord.Decided.class, decided -> {
                assertThat(decided.reason()).isEqualTo(DecisionReason.POLICY_DENIED);
                assertThat(decided.policyRevision()).isEqualTo(4L);
            });
        }
        assertThat(kit.fingerprint(legacy.folder())).as("the 2.x files are byte for byte what they were").isEqualTo(sourceBefore);
    }

    @Test
    void unknownOperationsStayUnknownAndNeedReconciliation(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("unknown").times(T0, T0 + 1000, T0 + 2000).transportLost().decision(EXECUTE),
            job(2).state("running").times(T0, T0 + 100, null).decision(EXECUTE)));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        try (Store store = kit.open(target)) {
            Operation recorded = store.byId(id(1));
            assertThat(recorded.state()).isEqualTo(OperationState.UNKNOWN);
            assertThat(recorded.settlement()).isInstanceOfSatisfying(Settlement.Settled.class, settled -> {
                assertThat(settled.certainty()).isEqualTo(OutcomeCertainty.UNKNOWN);
                assertThat(settled.reconciliation()).isEqualTo(Reconciliation.REQUIRED);
            });
            Operation inFlight = store.byId(id(2));
            assertThat(inFlight.state()).isEqualTo(OperationState.UNKNOWN);
            assertThat(inFlight.recovery()).isEqualTo(RecoveryReason.BRIDGE_RESTART);
            assertThat(inFlight.settlement()).isInstanceOfSatisfying(Settlement.Settled.class,
                settled -> assertThat(settled.reconciliation()).isEqualTo(Reconciliation.REQUIRED));
        }
    }

    @Test
    void anOperationTheOwnerApprovedKeepsTheOwnerAsStarter(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).approvalMode("owner"),
            job(2).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).readOnly().approvalMode("automatic")));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        try (Store store = kit.open(target)) {
            assertThat(store.byId(id(1)).approvalMode()).isEqualTo(ApprovalMode.OWNER);
            assertThat(store.events(id(1))).extracting(OperationEvent::state)
                .containsExactly(OperationState.PENDING, OperationState.QUEUED, OperationState.STARTING, OperationState.RUNNING, OperationState.COMPLETED);
            assertThat(store.byId(id(2)).approvalMode()).isEqualTo(ApprovalMode.AUTOMATIC);
        }
    }

    @Test
    void theRequestJournalsAreMovedAndAnOperationTheFormalJournalAlsoHoldsIsMovedOnce(@TempDir Path root) {
        LegacyJob sameKey = job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE).key("shared-key-0000000000000001");
        LegacyJob noKeyInTheJournal = job(4).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE);
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy"))
            .operations(List.of(sameKey, noKeyInTheJournal))
            .commandJournal(List.of(sameKey, noKeyInTheJournal, job(2).state("failed").times(T0, T0 + 1000, T0 + 2000).exited(1).decision(EXECUTE).withoutKey()))
            .agentCliJournal(List.of(job(3).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).key("agent-cli:0123456789abcdef0123456789abcdef")
                .command("npm install -g @example/agent-cli")));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("covered duplicates of another file: 2");
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(4);
            assertThat(store.byId(id(1)).requestKey().value()).isEqualTo("shared-key-0000000000000001");
            assertThat(store.byId(id(4)).requestKey().value()).isEqualTo("key-" + id(4));
            assertThat(store.byId(id(2)).requestKey().value()).as("the key 2.x gave a journal row without one").isEqualTo("legacy:command-automation.sqlite:" + id(2));
            assertThat(store.byId(id(3)).requestKey().value()).isEqualTo("agent-cli:0123456789abcdef0123456789abcdef");
            assertThat(store.commandOf(id(3))).isEqualTo("npm install -g @example/agent-cli");
        }
    }

    @Test
    void operationsOfOtherKindsAreReportedAsNotMovedAndTheFolderIsListedCompletely(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE),
            job(2).kind("upload").state("completed"),
            job(3).kind("download").state("completed"),
            job(4).kind("cli-probe").state("completed"))).otherFiles();
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("1 x operations.sqlite: upload operations not moved")
            .contains("1 x operations.sqlite: download operations not moved")
            .contains("1 x operations.sqlite: cli-probe operations not moved")
            .contains("MIGRATED  operations.sqlite")
            .contains("NOT_MIGRATED  maintenance.sqlite")
            .contains("NOT_MIGRATED  capacity.sqlite")
            .contains("NOT_MIGRATED  command-outputs")
            .contains("NOT_MIGRATED  " + "b".repeat(48) + ".part")
            .contains("UNRECOGNIZED  surprise.txt");
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(1);
            assertThat(store.has(id(2))).isFalse();
        }
    }

    @Test
    void anOperationWhoseRecordedOutcomeDoesNotConfirmItsStateIsStoredCarefullyAndSaidSo(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).decision(EXECUTE)));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        assertThat(run.output()).contains("stored differently from 2.x: operations.sqlite#" + id(1));
        try (Store store = kit.open(target)) {
            assertThat(store.byId(id(1)).state()).as("completed without evidence is unknown, never completed").isEqualTo(OperationState.UNKNOWN);
        }
    }

    @Test
    void aFolderWithoutAJournalIsAnErrorAndCreatesNothing(@TempDir Path root) throws Exception {
        Path empty = Files.createDirectories(root.resolve("legacy"));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(empty, target));

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("there is nothing to move");
        assertThat(target).doesNotExist();
    }

    @Test
    void aWrongCommandLineIsAnErrorWithTheUsage() {
        Run run = kit.run("--source=somewhere");

        assertThat(run.exitCode()).isEqualTo(1);
        assertThat(run.output()).contains("--target is required").contains("usage: opermand-migrate");
    }

    @Test
    void importingHistoryWithAnExternalDatasourceStillRequiresTarget(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE)));

        Run run = kit.run("--source=" + legacy.folder(), "--spring.datasource.url=jdbc:h2:mem:b7-no-target-test");

        assertThat(run.exitCode()).as(run.output()).isEqualTo(1);
        assertThat(run.output()).contains("--target is required when importing operation history with --spring.datasource.url");
    }

    @Test
    void importingHistoryStoresEncryptedAttachmentsAndOwnerOnlyKeyringUnderTarget(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(List.of(
            job(1).state("completed").times(T0, T0 + 1000, T0 + 2000).exited(0).decision(EXECUTE).command("echo secret-payload-check")));
        Path target = root.resolve("v3");

        Run run = kit.run(kit.importArgs(legacy.folder(), target));

        assertThat(run.exitCode()).as(run.output()).isZero();
        Path keyFile = target.resolve("keyring").resolve("attachment.keys");
        assertThat(keyFile).isRegularFile();
        LegacyEnvFixture.assertOwnerOnly(keyFile);
        assertThat(target.resolve("attachments")).isDirectory();
    }
}
