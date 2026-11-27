package io.github.jaeseongs95.opermand.app.operation;

/**
 * How many operations of unknown result wait for the owner, and how many the installation lets wait (defect D-0153). When the number that wait reaches the budget, a new
 * operation is refused with {@code OPERATION_RECONCILIATION_CAPACITY} and no queued one starts, and nothing but the owner's reconciliation lowers the number: this is what
 * a caller shows the owner next to that refusal, so the refusal names its cause and its way out ({@link OwnerReconciliation#awaiting}).
 *
 * <p>The budget bounds the operations that wait for a decision of the owner, not the history: no operation is deleted to make room, and a reconciled operation stays in the
 * journal as it is.
 *
 * @param waiting the operations in {@code UNKNOWN}
 * @param limit the budget for them: a new operation is refused once {@code waiting} has reached it
 */
public record ReconciliationBacklog(int waiting, int limit) {

    public ReconciliationBacklog {
        if (waiting < 0 || limit < 1) {
            throw new IllegalArgumentException("The waiting count is not negative and the budget is at least one");
        }
    }

    /** True when a new operation is refused because too many wait for the owner. */
    public boolean refusesNewWork() {
        return waiting >= limit;
    }

    /** How many of them the owner must reconcile before a new operation is admitted again; zero when it is admitted now. */
    public int toReconcile() {
        return Math.max(0, waiting - limit + 1);
    }
}
