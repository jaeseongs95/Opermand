package io.github.jaeseongs95.portal.app.operation;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;

import io.github.jaeseongs95.portal.app.audit.AuditAction;
import io.github.jaeseongs95.portal.app.audit.AuditEvent;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.app.journal.OperationQueueView;
import io.github.jaeseongs95.portal.app.journal.TargetFilter;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.Operation;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.operation.ReconciliationEvidence;
import io.github.jaeseongs95.portal.domain.schedule.ConcurrencyLimits;

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

    public OwnerReconciliationService(OperationJournal journal, OperationQueueView queueView, ConcurrencyLimits limits, OperationWrites writes, OutcomeAudit outcomes,
                                      Clock clock) {
        this.journal = journal;
        this.queueView = queueView;
        this.limits = limits;
        this.writes = writes;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    @Override
    public ReconciliationBacklog backlog() {
        return new ReconciliationBacklog(queueView.counts().of(OperationState.UNKNOWN), limits.unknownBudgetLimit().value());
    }

    @Override
    public List<Operation> awaiting(TargetFilter target, int limit) {
        return queueView.inStates(Set.of(OperationState.UNKNOWN), Objects.requireNonNull(target, "target"), limit);
    }

    @Override
    public ReconcileOutcome reconcile(OperationId id, ReconciliationEvidence evidence) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidence, "evidence");
        Optional<Operation> found = journal.find(id);
        if (found.isEmpty()) {
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
            return new ReconcileOutcome.Conflicted(journal.find(id).orElseThrow());
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
