package io.github.jaeseongs95.portal.app.operation;

import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.event.OperationEvents;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.domain.operation.Operation;

/**
 * Stores a new version of an operation inside a short write transaction and announces it only after the transaction commits: the event that wakes the waiters and the
 * dispatcher, the listeners of a terminal operation, and the timer of a waiting one.
 */
@Component
public class OperationWrites {

    private final OperationJournal journal;
    private final OperationEvents events;
    private final TerminalNotifier terminal;
    private final ExpiryTimers timers;
    private final TransactionOperations writeTx;

    @Autowired
    public OperationWrites(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, ExpiryTimers timers,
                           PlatformTransactionManager transactionManager) {
        this(journal, events, terminal, timers, new TransactionTemplate(transactionManager));
    }

    public OperationWrites(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, ExpiryTimers timers) {
        this(journal, events, terminal, timers, TransactionOperations.withoutTransaction());
    }

    private OperationWrites(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, ExpiryTimers timers, TransactionOperations writeTx) {
        this.journal = journal;
        this.events = events;
        this.terminal = terminal;
        this.timers = timers;
        this.writeTx = writeTx;
    }

    /** Stores {@code next} when the stored version is {@code expectedVersion}, then announces it after commit. Returns the stored operation. */
    public Operation store(Operation next, long expectedVersion) {
        return store(next, expectedVersion, () -> { });
    }

    /** The same, with a check that runs inside the transaction of the write, before it (the room in the queue; see {@link OperationJournal#save(Operation, long, Runnable)}). */
    public Operation store(Operation next, long expectedVersion, Runnable beforeWrite) {
        Operation stored = Objects.requireNonNull(writeTx.execute(status -> journal.save(next, expectedVersion, beforeWrite)), "stored");
        announce(stored);
        return stored;
    }

    /** Announces an operation that was stored: the event, the listeners of a terminal one and the timer of a waiting one. A new operation is announced after its audit lines. */
    public void announce(Operation stored) {
        events.changed(stored);
        if (stored.state().isTerminal()) {
            terminal.ended(stored);
        }
        timers.arm(stored);
    }
}
