package io.github.jaeseongs95.opermand.app.operation;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.audit.AuditAction;
import io.github.jaeseongs95.opermand.app.audit.AuditEvent;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.journal.OperationQueueView;
import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;

/**
 * {@link OwnerReconciliation} over the journal. The domain decides the move ({@code Operation.reconcile}: the only way out of {@code UNKNOWN}, the recorded result stays);
 * this class reads, asks, stores through {@link OperationWrites} (which announces the change, so the dispatcher wakes and the waiters and listeners hear of it) and writes the
 * audit line of the owner's decision after it is stored. A lost audit line never undoes the reconciliation: it marks the operation like any outcome audit does.
 *
 * <p>An operation that is not unknown, or one that another writer changed first, is an answer ({@link ReconcileOutcome}), not an error, and a version conflict is not tried
 * again: the owner looks at what is stored now and decides again.
 */
@Service
public class OwnerReconciliationService implements OwnerReconciliation {

    private final OperationJournal journal;
    private final OperationQueueView queueView;
    private final ConcurrencyLimits limits;
    private final OperationWrites writes;
    private final OutcomeAudit outcomes;
    private final Clock clock;
    private final TransactionOperations readTx;

    @Autowired
    public OwnerReconciliationService(OperationJournal journal, OperationQueueView queueView, ConcurrencyLimits limits, OperationWrites writes, OutcomeAudit outcomes,
                                      Clock clock, PlatformTransactionManager transactionManager) {
        this(journal, queueView, limits, writes, outcomes, clock, readOnly(transactionManager));
    }

    public OwnerReconciliationService(OperationJournal journal, OperationQueueView queueView, ConcurrencyLimits limits, OperationWrites writes, OutcomeAudit outcomes,
                                      Clock clock) {
        this(journal, queueView, limits, writes, outcomes, clock, TransactionOperations.withoutTransaction());
    }

    private OwnerReconciliationService(OperationJournal journal, OperationQueueView queueView, ConcurrencyLimits limits, OperationWrites writes, OutcomeAudit outcomes,
                                       Clock clock, TransactionOperations readTx) {
        this.journal = journal;
        this.queueView = queueView;
        this.limits = limits;
        this.writes = writes;
        this.outcomes = outcomes;
        this.clock = clock;
        this.readTx = readTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    @Override
    public ReconciliationBacklog backlog() {
        return Objects.requireNonNull(readTx.execute(status -> new ReconciliationBacklog(queueView.counts().of(OperationState.UNKNOWN), limits.unknownBudgetLimit().value())),
            "backlog");
    }

    @Override
    public List<Operation> awaiting(TargetFilter target, int limit) {
        Objects.requireNonNull(target, "target");
        return Objects.requireNonNull(readTx.execute(status -> queueView.inStates(Set.of(OperationState.UNKNOWN), target, limit)), "awaiting");
    }

    @Override
    public ReconcileOutcome reconcile(OperationId id, ReconciliationEvidence evidence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidence, "evidence");
        Optional<Operation> found = readTx.execute(status -> journal.find(id));
        if (found == null || found.isEmpty()) {
            return new ReconcileOutcome.NotFound(id);
        }
        Operation current = found.orElseThrow();
        if (current.state() != OperationState.UNKNOWN) {
            return new ReconcileOutcome.NotWaiting(current);
        }
        Operation stored;
        try {
            stored = writes.store(current.reconcile(evidence, clock.instant()), current.version());
        } catch (RequestRefusedException e) {
            if (e.code() != OperationErrorCode.OPERATION_VERSION_CONFLICT) {
                throw e;
            }
            Operation latest = Objects.requireNonNull(readTx.execute(status -> journal.find(id).orElseThrow()), "latest");
            return new ReconcileOutcome.Conflicted(latest);
        }
        outcomes.recordEvent(stored, new AuditEvent(AuditAction.RECONCILED, id, "evidence " + nameOf(evidence) + " state " + stored.state().wire()));
        return new ReconcileOutcome.Reconciled(stored, backlog());
    }

    private static String nameOf(ReconciliationEvidence evidence) {
        return switch (evidence) {
            case ReconciliationEvidence.Completed completed -> "completed";
            case ReconciliationEvidence.Failed failed -> "failed";
            case ReconciliationEvidence.NeverRan neverRan -> "never-ran";
        };
    }
}
