package io.github.jaeseongs95.opermand.store.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.jaeseongs95.opermand.app.journal.ClaimResult;
import io.github.jaeseongs95.opermand.domain.capacity.Capacity;
import io.github.jaeseongs95.opermand.domain.error.ExecutionErrorCode;
import io.github.jaeseongs95.opermand.domain.error.ExecutionFailure;
import io.github.jaeseongs95.opermand.domain.error.ExecutionStage;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RemoteOutcome;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.operation.ConflictKey;
import io.github.jaeseongs95.opermand.domain.operation.Deadline;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.opermand.domain.operation.NewOperation;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.opermand.domain.schedule.SaturationReason;
import io.github.jaeseongs95.opermand.store.TestSavepoints;

@JournalTest
class JdbcClaimStoreTest {

    private static final Instant AT = JournalFixture.NOW.plusSeconds(10);

    private final JdbcOperationJournal journal;
    private final JdbcClaimStore claims;
    private final JdbcTemplate jdbc;
    private final TestSavepoints savepoints;
    private final JournalFixture fx = new JournalFixture();

    JdbcClaimStoreTest(JdbcOperationJournal journal, JdbcClaimStore claims, JdbcTemplate jdbc, TestSavepoints savepoints) {
        this.journal = journal;
        this.claims = claims;
        this.jdbc = jdbc;
        this.savepoints = savepoints;
    }

    private static ConcurrencyLimits limits(int global, int target) {
        return ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(global)).withTargetConcurrency(new Capacity(target));
    }

    private Operation queued(String key, String target, ConflictKey conflictKey) {
        return fx.queued(journal, fx.command(key, target, "echo " + key, conflictKey));
    }

    private Operation claimed(Operation queued, ConcurrencyLimits limits) {
        ClaimResult result = claims.claim(queued.id(), queued.version(), limits, AT);
        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        return ((ClaimResult.Claimed) result).operation();
    }

    private Operation finished(Operation starting) {
        Operation running = journal.save(starting.markRunning(AT.plusSeconds(1)), starting.version());
        return journal.save(running.settle(ExecutionOutcome.exited(0, List.of()), AT.plusSeconds(2)), running.version());
    }

    private Operation lost(Operation starting) {
        Operation running = journal.save(starting.markRunning(AT.plusSeconds(1)), starting.version());
        ExecutionFailure failure = new ExecutionFailure.Classified(ExecutionStage.SSH_EXECUTION, ExecutionErrorCode.SSH_EXECUTION_FAILED, RemoteOutcome.UNKNOWN);
        return journal.save(running.settle(ExecutionOutcome.transportLost(failure, List.of()), AT.plusSeconds(2)), running.version());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    @Test
    @DisplayName("RC-B-202: a claim at an old version is not a claim; the right version moves the operation to STARTING and stores it; the same version cannot claim twice")
    void aClaimNeedsTheVersionItWasDecidedAt() {
        Operation queued = queued("one", "alpha", new ConflictKey.None());

        assertThat(claims.claim(queued.id(), queued.version() - 1, limits(2, 1), AT)).isInstanceOf(ClaimResult.NotQueued.class);
        ClaimResult claimed = claims.claim(queued.id(), queued.version(), limits(2, 1), AT);
        assertThat(claimed).isInstanceOf(ClaimResult.Claimed.class);
        Operation starting = ((ClaimResult.Claimed) claimed).operation();
        assertThat(starting.state()).isEqualTo(OperationState.STARTING);
        assertThat(journal.find(queued.id()).orElseThrow().snapshot()).isEqualTo(starting.snapshot());
        assertThat(claims.claim(queued.id(), queued.version(), limits(2, 1), AT)).isInstanceOf(ClaimResult.NotQueued.class);
        assertThatThrownBy(() -> journal.save(queued.deny(AT), queued.version())).isInstanceOfSatisfying(RequestRefusedException.class,
            e -> assertThat(e.code()).isEqualTo(OperationErrorCode.OPERATION_VERSION_CONFLICT));
    }

    @Test
    @DisplayName("S2-DB-05: the global limit is a count of slots: exactly the limit is claimed, the next claim is Saturated(GLOBAL_CONCURRENCY), a finished operation frees its slot")
    void theGlobalLimitIsKept() {
        ConcurrencyLimits two = limits(2, 1);
        Operation a = queued("a", "alpha", new ConflictKey.None());
        Operation b = queued("b", "beta", new ConflictKey.None());
        Operation c = queued("c", "gamma", new ConflictKey.None());

        Operation startingA = claimed(a, two);
        claimed(b, two);
        assertThat(claims.claim(c.id(), c.version(), two, AT)).isEqualTo(new ClaimResult.Saturated(SaturationReason.GLOBAL_CONCURRENCY));
        assertThat(count("operation_slot")).isEqualTo(4);

        finished(startingA);
        assertThat(count("operation_slot")).isEqualTo(2);
        assertThat(claims.claim(c.id(), c.version(), two, AT)).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("S2-DB-05: the limit of a target is kept apart from the global limit: Saturated(TARGET_CONCURRENCY) while the global limit has room")
    void theTargetLimitIsKept() {
        ConcurrencyLimits limits = limits(5, 1);
        Operation first = queued("first", "alpha", new ConflictKey.None());
        Operation second = queued("second", "alpha", new ConflictKey.None());
        Operation other = queued("other", "beta", new ConflictKey.None());

        claimed(first, limits);
        assertThat(claims.claim(second.id(), second.version(), limits, AT)).isEqualTo(new ClaimResult.Saturated(SaturationReason.TARGET_CONCURRENCY));
        assertThat(claims.claim(other.id(), other.version(), limits, AT)).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("RC-B-198: a limit that was lowered is applied to the next claim: slots above the new limit stay until they are freed but never count as free")
    void aLoweredLimitApplies() {
        Operation a = queued("a", "alpha", new ConflictKey.None());
        Operation b = queued("b", "beta", new ConflictKey.None());
        Operation c = queued("c", "gamma", new ConflictKey.None());
        claimed(a, limits(2, 1));
        claimed(b, limits(2, 1));

        assertThat(claims.claim(c.id(), c.version(), limits(1, 1), AT)).isEqualTo(new ClaimResult.Saturated(SaturationReason.GLOBAL_CONCURRENCY));
    }

    @Test
    @DisplayName("S2-DB-05: the unknown budget is a bound on new claims: an operation whose outcome is unknown keeps its place in the budget (RC-B-207)")
    void theUnknownBudgetBoundsNewClaims() {
        ConcurrencyLimits limits = limits(5, 5).withUnknownBudget(new Capacity(1));
        Operation first = queued("first", "alpha", new ConflictKey.None());
        Operation second = queued("second", "beta", new ConflictKey.None());

        Operation unknown = lost(claimed(first, limits));
        assertThat(unknown.state()).isEqualTo(OperationState.UNKNOWN);
        assertThat(count("operation_slot")).as("an unknown operation holds no slot").isZero();
        assertThat(claims.claim(second.id(), second.version(), limits, AT)).isEqualTo(new ClaimResult.Saturated(SaturationReason.UNKNOWN_BUDGET));
    }

    @Test
    @DisplayName("RC-B-206: a conflict key is held by one live operation per target revision; an unknown operation keeps holding it until it is reconciled")
    void aConflictKeySerializesOperations() {
        ConcurrencyLimits limits = limits(10, 10);
        Operation first = queued("first", "alpha", new ConflictKey.Of("installer"));
        Operation second = queued("second", "alpha", new ConflictKey.Of("installer"));
        Operation independent = queued("independent", "alpha", new ConflictKey.Of("independent"));

        Operation startingFirst = claimed(first, limits);
        assertThatThrownBy(() -> savepoints.run(() -> claims.claim(second.id(), second.version(), limits, AT)))
            .as("the key is held while the first runs; DuplicateKeyException propagates so the caller's transaction rolls back cleanly")
            .isInstanceOf(DuplicateKeyException.class);
        claimed(independent, limits);

        Operation unknown = lost(startingFirst);
        assertThatThrownBy(() -> savepoints.run(() -> claims.claim(second.id(), second.version(), limits, AT)))
            .as("an operation of unknown outcome keeps its key")
            .isInstanceOf(DuplicateKeyException.class);

        journal.save(unknown.reconcile(new ReconciliationEvidence.Failed(), AT.plusSeconds(30)), unknown.version());
        assertThat(claims.claim(second.id(), second.version(), limits, AT)).as("a reconciled operation frees its key").isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("RC-B-206: a conflict key of another target revision is another fence")
    void theFenceIsPerTargetRevision() {
        ConcurrencyLimits limits = limits(10, 10);
        Operation first = queued("first", "alpha", new ConflictKey.Of("installer"));
        NewOperation revised = fx.command("revised", "alpha", "echo revised", "owner", "1".repeat(64), new ConflictKey.Of("installer"), new Deadline.None());
        Operation other = fx.queued(journal, revised);

        claimed(first, limits);
        assertThat(claims.claim(other.id(), other.version(), limits, AT)).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    @DisplayName("B4-TX: claiming with an instant slightly behind updatedAt clamps to updatedAt so clock skew does not fail the claim")
    void claimClampsInstantBehindUpdatedAt() {
        Operation queued = queued("skew", "alpha", new ConflictKey.None());
        Instant behind = queued.updatedAt().minusSeconds(5);

        ClaimResult result = claims.claim(queued.id(), queued.version(), limits(2, 1), behind);

        assertThat(result).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(((ClaimResult.Claimed) result).operation().updatedAt()).isEqualTo(queued.updatedAt());
    }
}
