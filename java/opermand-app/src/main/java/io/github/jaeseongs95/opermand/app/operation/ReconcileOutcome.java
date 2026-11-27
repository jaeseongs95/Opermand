package io.github.jaeseongs95.opermand.app.operation;

import java.util.Objects;

import io.github.jaeseongs95.opermand.domain.id.OperationId;
import io.github.jaeseongs95.opermand.domain.operation.Operation;

/**
 * The answer of {@link OwnerReconciliation#reconcile}: what the owner's comparison did, and why it did nothing when it did nothing. Every variant is an expected answer of a
 * caller that may click twice or race another one, so none is an exception; a caller answers each with its own message.
 */
public sealed interface ReconcileOutcome permits ReconcileOutcome.Reconciled, ReconcileOutcome.NotFound, ReconcileOutcome.NotWaiting, ReconcileOutcome.Conflicted {

    /** The operation left {@code UNKNOWN} for the state the evidence established; {@code backlog} is what still waits afterwards. */
    record Reconciled(Operation operation, ReconciliationBacklog backlog) implements ReconcileOutcome {

        public Reconciled {
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(backlog, "backlog");
        }
    }

    /** No operation has this id. */
    record NotFound(OperationId id) implements ReconcileOutcome {

        public NotFound {
            Objects.requireNonNull(id, "id");
        }
    }

    /** The operation is not unknown (it never was, or an earlier reconciliation settled it): nothing was changed. {@code current} is what is stored. */
    record NotWaiting(Operation current) implements ReconcileOutcome {

        public NotWaiting {
            Objects.requireNonNull(current, "current");
        }
    }

    /** Another writer changed the operation while the owner looked at it: nothing was changed, {@code latest} is what is stored now and the owner decides again. */
    record Conflicted(Operation latest) implements ReconcileOutcome {

        public Conflicted {
            Objects.requireNonNull(latest, "latest");
        }
    }
}
