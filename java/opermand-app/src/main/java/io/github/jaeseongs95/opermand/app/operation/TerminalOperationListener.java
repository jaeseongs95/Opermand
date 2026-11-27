package io.github.jaeseongs95.opermand.app.operation;

import io.github.jaeseongs95.opermand.domain.operation.Operation;

/**
 * Something that has to happen once when an operation reaches a terminal state: the file service unpins the artifact of an upload, the output store starts the clock of
 * the retention. The listeners are beans; adding one does not change a use case (open for extension). A listener is idempotent, because {@code SettlementRecovery} tells it
 * again, at the start and every minute, about an operation that ended and still holds an artifact or an action (a call that was lost or failed is made up for from the stored
 * state, defect D-0157). A failure of a listener is logged and never changes the operation.
 */
public interface TerminalOperationListener {

    void onTerminal(Operation operation);
}
