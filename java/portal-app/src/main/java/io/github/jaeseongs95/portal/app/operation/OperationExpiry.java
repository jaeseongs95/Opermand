package io.github.jaeseongs95.portal.app.operation;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.portal.app.event.OperationEvents;
import io.github.jaeseongs95.portal.app.journal.OperationJournal;
import io.github.jaeseongs95.portal.domain.error.OperationErrorCode;
import io.github.jaeseongs95.portal.domain.error.RequestRefusedException;
import io.github.jaeseongs95.portal.domain.id.OperationId;
import io.github.jaeseongs95.portal.domain.operation.Operation;

/**
 * Expires an operation whose approval deadline has passed before it was dispatched (2.x: no reaper, the deadline wakes the waiters, cases A-146, A-147, B-196).
 */
@Component
public class OperationExpiry {

    private final OperationJournal journal;
    private final OperationEvents events;
    private final TerminalNotifier terminal;
    private final OutcomeAudit outcomes;
    private final Clock clock;
    private final TransactionOperations writeTx;

    @Autowired
    public OperationExpiry(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, OutcomeAudit outcomes, Clock clock,
                           PlatformTransactionManager transactionManager) {
        this(journal, events, terminal, outcomes, clock, new TransactionTemplate(transactionManager));
    }

    public OperationExpiry(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, OutcomeAudit outcomes, Clock clock) {
        this(journal, events, terminal, outcomes, clock, TransactionOperations.withoutTransaction());
    }

    private OperationExpiry(OperationJournal journal, OperationEvents events, TerminalNotifier terminal, OutcomeAudit outcomes, Clock clock,
                            TransactionOperations writeTx) {
        this.journal = journal;
        this.events = events;
        this.terminal = terminal;
        this.outcomes = outcomes;
        this.clock = clock;
        this.writeTx = writeTx;
    }

    /** True when this call expired the operation; false when it was not waiting, not late, or another writer changed it first. */
    public boolean expireIfDue(OperationId id) {
        Operation stored;
        try {
            stored = writeTx.execute(status -> {
                Optional<Operation> found = journal.find(id);
                if (found.isEmpty()) {
                    return null;
                }
                Operation operation = found.orElseThrow();
                Instant now = clock.instant();
                if (!operation.state().isPreDispatch() || !operation.expiry().isReached(now)) {
                    return null;
                }
                return journal.save(operation.expire(now), operation.version());
            });
        } catch (RequestRefusedException e) {
            if (e.code() == OperationErrorCode.OPERATION_VERSION_CONFLICT) {
                return false;
            }
            throw e;
        }
        if (stored == null) {
            return false;
        }
        events.changed(stored);
        terminal.ended(stored);
        outcomes.recordOutcome(stored);
        return true;
    }
}
