package io.github.jaeseongs95.portal.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.journal.TargetFilter;
import io.github.jaeseongs95.portal.app.operation.OperationIntake;
import io.github.jaeseongs95.portal.app.operation.OwnerReconciliation;
import io.github.jaeseongs95.portal.app.operation.ReconcileOutcome;
import io.github.jaeseongs95.portal.app.operation.ReconciliationBacklog;
import io.github.jaeseongs95.portal.domain.capacity.Capacity;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.CompletionEvidence;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.operation.Reconciliation;
import io.github.jaeseongs95.portal.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.portal.domain.operation.Settlement;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;

/**
 * The owner's way out of operations of unknown result with the real journal, claim store and use cases over the embedded H2 database (defect D-0153; 2.x had no way out and
 * a bridge with 32 unknown operations refused every command, defect D-0152). The budget is two here, so a test reaches it with two operations; the rule is the same one that
 * stops at 32 in a new installation. Only the SSH executor, the audit port and the target platform are plain test classes.
 */
@UseCaseTest
class OwnerReconciliationTest {

    private static final ReconciliationEvidence NEVER_RAN = new ReconciliationEvidence.NeverRan();

    /** The slice with a budget of two unknown operations; it is the {@code ConcurrencyLimits} of this test class (a context of its own, so no other test leaves an unknown operation in it). */
    @TestConfiguration(proxyBeanMethods = false)
    static class TwoUnknownBudget {

        @Bean
        @Primary
        ConcurrencyLimits twoUnknownLimits() {
            return ConcurrencyLimits.defaults().withGlobalConcurrency(new Capacity(4)).withTargetConcurrency(new Capacity(2)).withUnknownBudget(new Capacity(2));
        }
    }

    private final OperationIntake intake;
    private final OwnerReconciliation reconciliation;
    private final ScriptedExecutor executor;
    private final RecordingAudit audit;
    private final FixedTargets targets;
    private final UseCaseKit kit;

    OwnerReconciliationTest(OperationIntake intake, OwnerReconciliation reconciliation, ScriptedExecutor executor, RecordingAudit audit, FixedTargets targets, UseCaseKit kit) {
        this.intake = intake;
        this.reconciliation = reconciliation;
        this.executor = executor;
        this.audit = audit;
        this.targets = targets;
        this.kit = kit;
    }

    @BeforeEach
    void start() {
        executor.reset();
        audit.failNothing();
        targets.reset();
        for (Operation waiting : reconciliation.awaiting(new TargetFilter.AnyTarget(), 100)) {
            reconciliation.reconcile(waiting.id(), NEVER_RAN);
        }
    }

    private Operation submitApproved(String text) {
        Operation submitted = intake.submit(kit.command(text));
        intake.approve(submitted.id());
        return submitted;
    }

    private Operation unknownOperation(String text) throws Exception {
        executor.when("explodes", executor.failsUnexpectedly());
        Operation exploded = submitApproved("explodes " + text);
        assertThat(kit.awaitTerminal(exploded.id()).state()).isEqualTo(OperationState.UNKNOWN);
        return exploded;
    }

    @Test
    @DisplayName("S-D0153-01 D-0153 when the unknown budget is used up a new operation is refused with OPERATION_RECONCILIATION_CAPACITY and the backlog says why; the owner's reconciliation opens the queue again and sends nothing to the target")
    void theBudgetRefusesNewWorkUntilTheOwnerReconciles() throws Exception {
        Operation first = unknownOperation("one");
        Operation second = unknownOperation("two");
        Operation third = intake.submit(kit.command("touch third"));

        assertThatThrownBy(() -> intake.approve(third.id())).isInstanceOfSatisfying(RequestRefusedException.class,
            refused -> assertThat(refused.code()).isEqualTo(OperationErrorCode.OPERATION_RECONCILIATION_CAPACITY));
        ReconciliationBacklog full = reconciliation.backlog();
        assertThat(full).isEqualTo(new ReconciliationBacklog(2, 2));
        assertThat(full.refusesNewWork()).isTrue();
        assertThat(full.toReconcile()).as("one reconciliation is enough to admit again").isEqualTo(1);
        assertThat(reconciliation.awaiting(new TargetFilter.AnyTarget(), 10)).extracting(Operation::id).containsExactlyInAnyOrder(first.id(), second.id());

        ReconcileOutcome outcome = reconciliation.reconcile(first.id(), NEVER_RAN);

        assertThat(outcome).isInstanceOfSatisfying(ReconcileOutcome.Reconciled.class, reconciled -> {
            assertThat(reconciled.operation().state()).isEqualTo(OperationState.CANCELLED);
            assertThat(((Settlement.Settled) reconciled.operation().settlement()).reconciliation()).isEqualTo(Reconciliation.NOT_REQUIRED);
            assertThat(reconciled.backlog()).isEqualTo(new ReconciliationBacklog(1, 2));
            assertThat(reconciled.backlog().refusesNewWork()).isFalse();
        });
        assertThat(kit.find(first.id()).state()).isEqualTo(OperationState.CANCELLED);
        assertThat(executor.requestsSent()).as("reconciling sends nothing again").isEqualTo(2);
        assertThat(audit.actionsOf(first.id())).endsWith(AuditAction.RECONCILED);
        intake.approve(third.id());
        assertThat(kit.awaitTerminal(third.id()).state()).isEqualTo(OperationState.COMPLETED);
        assertThat(executor.sentLines()).containsExactly("explodes one", "explodes two", "touch third");
    }

    @Test
    @DisplayName("S-D0153-02 D-0153 a reconciliation that cannot apply is an answer: an unknown id is NotFound, an operation that is not unknown is NotWaiting, a second call changes nothing; completed and failed evidence settle as such")
    void aReconciliationThatCannotApplyIsAnAnswer() throws Exception {
        Operation completed = unknownOperation("completed");
        Operation failed = unknownOperation("failed");

        assertThat(reconciliation.reconcile(new OperationId("f".repeat(48)), NEVER_RAN)).isInstanceOf(ReconcileOutcome.NotFound.class);
        assertThat(reconciliation.reconcile(completed.id(), new ReconciliationEvidence.Completed(new CompletionEvidence.CommandExited(0))))
            .isInstanceOfSatisfying(ReconcileOutcome.Reconciled.class, reconciled -> assertThat(reconciled.operation().state()).isEqualTo(OperationState.COMPLETED));
        assertThat(reconciliation.reconcile(failed.id(), new ReconciliationEvidence.Failed()))
            .isInstanceOfSatisfying(ReconcileOutcome.Reconciled.class, reconciled -> assertThat(reconciled.operation().state()).isEqualTo(OperationState.FAILED));

        long version = kit.find(completed.id()).version();
        assertThat(reconciliation.reconcile(completed.id(), NEVER_RAN)).isInstanceOfSatisfying(ReconcileOutcome.NotWaiting.class,
            notWaiting -> assertThat(notWaiting.current().state()).isEqualTo(OperationState.COMPLETED));
        assertThat(kit.find(completed.id()).version()).as("nothing was written").isEqualTo(version);
        assertThat(reconciliation.backlog().waiting()).isZero();
        assertThat(executor.requestsSent()).isEqualTo(2);
        assertThat(audit.actionsOf(completed.id())).containsOnlyOnce(AuditAction.RECONCILED);
    }

    @Test
    @DisplayName("S-D0153-03 D-0153 an operation that waits in the queue because the unknown operations use the budget starts when the owner reconciles one, with no other event")
    void aReconciliationWakesTheQueue() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        executor.when("blocker", executor.blocksUntil(release));
        Operation unknown = unknownOperation("waiting");
        Operation blocker = submitApproved("blocker");
        await().atMost(Duration.ofSeconds(10)).until(() -> kit.find(blocker.id()).state() == OperationState.RUNNING);

        Operation queued = submitApproved("touch queued");

        await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(kit.find(queued.id()).state())
            .as("a running operation and an unknown one use the budget of two").isEqualTo(OperationState.QUEUED));
        assertThat(reconciliation.reconcile(unknown.id(), NEVER_RAN)).isInstanceOf(ReconcileOutcome.Reconciled.class);

        assertThat(kit.awaitTerminal(queued.id()).state()).isEqualTo(OperationState.COMPLETED);
        assertThat(kit.find(blocker.id()).state()).as("the blocker still runs: the reconciliation started the queued one").isEqualTo(OperationState.RUNNING);
        release.countDown();
        assertThat(kit.awaitTerminal(blocker.id()).state()).isEqualTo(OperationState.COMPLETED);
        assertThat(List.copyOf(executor.sentLines())).containsExactlyInAnyOrder("explodes waiting", "blocker", "touch queued");
    }
}
