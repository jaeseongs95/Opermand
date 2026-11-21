package io.github.jaeseongs95.portal.migrate;

import static io.github.jaeseongs95.portal.migrate.LegacyJob.T0;
import static io.github.jaeseongs95.portal.migrate.LegacyJob.job;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.jaeseongs95.portal.app.journal.ClaimResult;
import io.github.jaeseongs95.portal.domain.capacity.Capacity;
import io.github.jaeseongs95.portal.domain.id.RequestKey;
import io.github.jaeseongs95.portal.domain.id.Sha256Digester;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.id.OwnerId;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.Admission;
import io.github.jaeseongs95.portal.domain.operation.CommandInput;
import io.github.jaeseongs95.portal.domain.operation.ConflictKey;
import io.github.jaeseongs95.portal.domain.operation.Deadline;
import io.github.jaeseongs95.portal.domain.operation.DecisionReason;
import io.github.jaeseongs95.portal.domain.operation.NewOperation;
import io.github.jaeseongs95.portal.domain.operation.OperationKind;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.portal.domain.schedule.SaturationReason;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.text.CommandText;
import io.github.jaeseongs95.portal.domain.text.Utf8Text;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Run;
import io.github.jaeseongs95.portal.migrate.MigrationKit.Store;

/**
 * Requirement 7 (defects D-0152, D-0153: 2.4.2 stopped every new command because 32 operations were unknown). The 3.0 store counts every unknown operation, imported ones
 * included, against the unknown budget, so the migration refuses a result that would use the budget up, and says why; the count is proved against the store's own claim.
 */
class MigrateUnknownTest {

    private static final String EXECUTE = "{\"decision\":\"execute\",\"reasonCode\":\"command_set\",\"policyRevision\":1}";

    private final MigrationKit kit = new MigrationKit();

    private static String id(int number) {
        return String.format("%048x", number);
    }

    private static List<LegacyJob> unknown(int from, int count) {
        List<LegacyJob> jobs = new ArrayList<>();
        for (int number = from; number < from + count; number++) {
            jobs.add(job(number).state("running").times(T0, T0 + 5, null).decision(EXECUTE));
        }
        return jobs;
    }

    @Test
    void aMigrationThatWouldUseTheUnknownBudgetUpIsRefusedAndWritesNothing(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(unknown(1, 3));
        Path target = root.resolve("v3");

        Run refused = kit.run(kit.importArgs(legacy.folder(), target, "--unknown-budget=3"));

        assertThat(refused.exitCode()).as(refused.output()).isEqualTo(2);
        assertThat(refused.output()).contains("CONFLICT UNKNOWN_BUDGET").contains("the store would hold 3 unknown operations (0 now, 3 to move) and the unknown budget is 3")
            .contains("Nothing was written");
        assertThat(target).as("a move that cannot be made leaves no store (and no folder) behind").doesNotExist();

        Run allowed = kit.run(kit.importArgs(legacy.folder(), target, "--unknown-budget=4"));

        assertThat(allowed.exitCode()).as(allowed.output()).isZero();
        try (Store store = kit.open(target)) {
            assertThat(store.counts().get(OperationState.UNKNOWN)).isEqualTo(3);
        }
    }

    @Test
    void theUnknownOperationsAlreadyInTheStoreCountToo(@TempDir Path root) {
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(new LegacyFixture(root.resolve("legacy-1")).operations(unknown(1, 2)).folder(), target, "--unknown-budget=10")).exitCode()).isZero();
        LegacyFixture second = new LegacyFixture(root.resolve("legacy-2")).operations(unknown(3, 2));

        Run refused = kit.run(kit.importArgs(second.folder(), target, "--unknown-budget=4"));

        assertThat(refused.exitCode()).as(refused.output()).isEqualTo(2);
        assertThat(refused.output()).contains("the store would hold 4 unknown operations (2 now, 2 to move)");
        try (Store store = kit.open(target)) {
            assertThat(store.total()).isEqualTo(2);
        }
        Run allowed = kit.run(kit.importArgs(second.folder(), target, "--unknown-budget=5"));
        assertThat(allowed.exitCode()).as(allowed.output()).isZero();
    }

    @Test
    void theDryRunAnswersTheBudgetQuestionWithoutAStore(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(unknown(1, 3));
        Path target = root.resolve("v3");

        Run refused = kit.run(kit.dryRunArgs(legacy.folder(), target, "--unknown-budget=3"));

        assertThat(refused.exitCode()).as(refused.output()).isEqualTo(2);
        assertThat(target).doesNotExist();
        assertThat(kit.run(kit.dryRunArgs(legacy.folder(), target, "--unknown-budget=4")).exitCode()).isZero();
    }

    @Test
    void movedUnknownOperationsUseTheBudgetOfTheStoresOwnClaim(@TempDir Path root) {
        LegacyFixture legacy = new LegacyFixture(root.resolve("legacy")).operations(unknown(1, 2));
        Path target = root.resolve("v3");
        assertThat(kit.run(kit.importArgs(legacy.folder(), target, "--unknown-budget=3")).exitCode()).isZero();

        try (Store store = kit.open(target)) {
            // A new operation that a policy lets run is queued; the claim is what the dispatcher of the 3.0 server does next.
            NewOperation request = NewOperation.of(new Sha256Digester(), new RequestKey("new-operation-key-0000000001"), new OwnerId("mcp"), new TargetId("prod"),
                TargetRevision.of("a".repeat(64)), new ProfileAlias("aws-local"), new CommandInput(new CommandText("uptime"), new Utf8Text(""), new Utf8Text("")),
                new ConflictKey.None(), new Deadline.None());
            store.journal().accept(request, Admission.of(DecisionReason.COMMAND_SET, OperationKind.COMMAND, 1L));
            OperationId queued = store.journal().findByRequestKey(request.requestKey()).orElseThrow().id();
            long version = store.journal().find(queued).orElseThrow().version();

            ClaimResult tooSmall = store.claims().claim(queued, version, ConcurrencyLimits.defaults().withUnknownBudget(new Capacity(2)), Instant.parse("2026-11-20T00:00:00Z"));

            assertThat(tooSmall).as("two moved unknown operations use a budget of 2 up, so the server would start nothing new").isInstanceOfSatisfying(ClaimResult.Saturated.class,
                saturated -> assertThat(saturated.reason()).isEqualTo(SaturationReason.UNKNOWN_BUDGET));

            ClaimResult roomLeft = store.claims().claim(queued, version, ConcurrencyLimits.defaults().withUnknownBudget(new Capacity(3)), Instant.parse("2026-11-20T00:00:00Z"));

            assertThat(roomLeft).as("with the budget the migration was told, a new operation is still started").isInstanceOf(ClaimResult.Claimed.class);
        }
    }
}
