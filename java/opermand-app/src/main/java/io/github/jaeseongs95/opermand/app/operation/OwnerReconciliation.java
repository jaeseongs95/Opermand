package io.github.jaeseongs95.opermand.app.operation;

import java.util.List;

import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.ReconciliationEvidence;

/**
 * The owner's way out of operations of unknown result (defect D-0153; 2.x had none, defect D-0152): list what waits, see how close the installation is to refusing new
 * work, and settle one operation by what the owner found on the target. This is the port that the entrance of the owner (the management API and screen) calls; it is
 * the only place where an {@code UNKNOWN} operation leaves that state ({@code Operation.reconcile}).
 *
 * <p><b>Nothing runs again.</b> Reconciling records what the owner established and sends nothing to the target; no operation is replayed by it or after it. The call does not
 * check the revision of the target: the owner may have to close the operations of a target that was changed or removed meanwhile, and a closed operation changes nothing there.
 * Who may call it is the entrance's decision (only the owner), not this port's.
 *
 * <p>A caller that got {@code OPERATION_RECONCILIATION_CAPACITY} from an intake or an approval asks {@link #backlog()} to tell the owner how many operations wait and how
 * many the budget allows, and {@link #awaiting} for the operations to reconcile: that code is retryable only after the owner acted.
 */
public interface OwnerReconciliation {

    /** How many operations of unknown result wait and how many the budget allows. */
    ReconciliationBacklog backlog();

    /** The operations of unknown result that wait for the owner, oldest first, at most {@code limit}. */
    List<Operation> awaiting(TargetFilter target, int limit);

    /**
     * Settles the unknown operation {@code id} by the evidence of the owner. The stored operation leaves {@code UNKNOWN} for completed, failed or cancelled, the waiting
     * queue is woken (a freed budget lets queued operations start), and an audit line {@code reconciled} follows the stored result.
     */
    ReconcileOutcome reconcile(OperationId id, ReconciliationEvidence evidence);
}
