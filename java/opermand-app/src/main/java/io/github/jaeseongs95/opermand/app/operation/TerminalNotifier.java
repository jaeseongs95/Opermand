package io.github.jaeseongs95.opermand.app.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.github.jaeseongs95.opermand.domain.operation.Operation;

/**
 * Tells every {@link TerminalOperationListener} that an operation ended, in the order of the listeners, when the operation ends. A listener that fails does not stop the others.
 * The call is made in memory, so it is not the only way a listener hears of an end: an application that stops before the call, or a listener that fails, would leave what the
 * listener settles undone. {@code SettlementRecovery} finds those leftovers in the stored state (a pinned artifact, a submitted action of a staged file, whose operation is in a
 * final state) at the start and every minute, and tells the listeners again through this class, so a listener has to be idempotent (defect D-0157).
 */
@Component
public class TerminalNotifier {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalNotifier.class);

    private final ObjectProvider<TerminalOperationListener> listeners;

    public TerminalNotifier(ObjectProvider<TerminalOperationListener> listeners) {
        this.listeners = listeners;
    }

    public void ended(Operation operation) {
        listeners.orderedStream().forEach(listener -> {
            try {
                listener.onTerminal(operation);
            } catch (RuntimeException e) {
                LOG.error("A listener of terminal operations failed; the operation is unchanged", e);
            }
        });
    }
}
