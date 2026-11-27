package io.github.jaeseongs95.opermand.app.recovery;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.jaeseongs95.opermand.app.event.OperationEvents;
import io.github.jaeseongs95.opermand.app.execution.ExecutionDispatcher;
import io.github.jaeseongs95.opermand.app.journal.OperationQueueView;
import io.github.jaeseongs95.opermand.app.journal.RestartRecovery;
import io.github.jaeseongs95.opermand.app.journal.TargetFilter;
import io.github.jaeseongs95.opermand.app.lifecycle.StartupSequence;
import io.github.jaeseongs95.opermand.app.lifecycle.StartupStep;
import io.github.jaeseongs95.opermand.app.operation.ExpiryTimers;
import io.github.jaeseongs95.opermand.app.operation.TerminalNotifier;
import io.github.jaeseongs95.opermand.domain.error.OperationErrorCode;
import io.github.jaeseongs95.opermand.domain.error.PortalException;
import io.github.jaeseongs95.opermand.domain.error.RequestRefusedException;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationState;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;

/**
 * What the start of the application does about the operations that were in flight when it last stopped (stage 3 design 8.2 group G8, cases B-493 to B-495, T18 to T21).
 * The journal closes them in one transaction ({@link RestartRecovery}): what was running or being evaluated becomes {@code UNKNOWN} with the reason {@code BRIDGE_RESTART}
 * and the owner reconciles it, what waited for the owner expires, what was only queued stays queued. <b>Nothing is sent again.</b> This class then announces what changed
 * (the waiters, the listeners of terminal operations), settles what an operation that ended left half done ({@link SettlementRecovery}: a pinned artifact, an open action on a
 * staged file, a reserved action that was never accepted), arms the timers of the operations that still wait, and opens the dispatcher for the queue that remains.
 *
 * <p><b>When it runs.</b> It is the last {@link StartupStep} ({@link StartupSequence} calls {@link #run()} once per bean factory, after all singletons exist and after the file
 * stores were checked and reconciled: a command that starts here reserves an output, and a pending output of the last run must have been given up before it, defect D-0159).
 * The product has one parent (non-web) context and two child web contexts; the refresh event of a child is also delivered to the listeners of the parent, so a
 * {@code ContextRefreshedEvent} listener would recover the journal more than once. The sequence belongs to the factory that owns it, so it runs once.
 * The dispatcher is closed until this method has finished, so the first claim never runs before the recovery. A recovery that cannot be stored stops the start with
 * {@code OPERATION_STORE_FAILED} and the dispatcher stays closed: serving requests over a journal that was not recovered would claim operations that the last run
 * still holds.
 */
@Component
@Order(StartupStep.JOURNAL)
public class StartupRecovery implements StartupStep {

    private static final Logger LOG = LoggerFactory.getLogger(StartupRecovery.class);

    private final RestartRecovery recovery;
    private final OperationQueueView queueView;
    private final OperationEvents events;
    private final TerminalNotifier terminal;
    private final SettlementRecovery settlements;
    private final ExpiryTimers timers;
    private final ExecutionDispatcher dispatcher;
    private final ConcurrencyLimits limits;
    private final Clock clock;
    private final TransactionOperations readTx;
    private final TransactionOperations writeTx;

    @Autowired
    public StartupRecovery(RestartRecovery recovery, OperationQueueView queueView, OperationEvents events, TerminalNotifier terminal, SettlementRecovery settlements,
                           ExpiryTimers timers, ExecutionDispatcher dispatcher, ConcurrencyLimits limits, Clock clock, PlatformTransactionManager transactionManager) {
        this(recovery, queueView, events, terminal, settlements, timers, dispatcher, limits, clock,
            readOnly(transactionManager), new TransactionTemplate(transactionManager));
    }

    public StartupRecovery(RestartRecovery recovery, OperationQueueView queueView, OperationEvents events, TerminalNotifier terminal, SettlementRecovery settlements,
                           ExpiryTimers timers, ExecutionDispatcher dispatcher, ConcurrencyLimits limits, Clock clock) {
        this(recovery, queueView, events, terminal, settlements, timers, dispatcher, limits, clock,
            TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
    }

    private StartupRecovery(RestartRecovery recovery, OperationQueueView queueView, OperationEvents events, TerminalNotifier terminal, SettlementRecovery settlements,
                            ExpiryTimers timers, ExecutionDispatcher dispatcher, ConcurrencyLimits limits, Clock clock,
                            TransactionOperations readTx, TransactionOperations writeTx) {
        this.recovery = recovery;
        this.queueView = queueView;
        this.events = events;
        this.terminal = terminal;
        this.settlements = settlements;
        this.timers = timers;
        this.dispatcher = dispatcher;
        this.limits = limits;
        this.clock = clock;
        this.readTx = readTx;
        this.writeTx = writeTx;
    }

    private static TransactionOperations readOnly(PlatformTransactionManager transactionManager) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setReadOnly(true);
        return tx;
    }

    @Override
    public void run() {
        recover();
    }

    /** Recovers the journal in one write transaction, announces what changed, settles leftovers, and opens the dispatcher. */
    public List<Operation> recover() {
        List<Operation> changed;
        try {
            changed = Objects.requireNonNull(writeTx.execute(status -> recovery.recoverAfterRestart(clock.instant())), "changed");
        } catch (PortalException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RequestRefusedException(OperationErrorCode.OPERATION_STORE_FAILED, e);
        }
        for (Operation operation : changed) {
            events.changed(operation);
            if (operation.state().isTerminal()) {
                terminal.ended(operation);
            }
        }
        settlements.settleAfterRestart();
        List<Operation> queued = Objects.requireNonNull(
            readTx.execute(status -> queueView.inStates(Set.of(OperationState.QUEUED), new TargetFilter.AnyTarget(), limits.queueCapacity().value())), "queued");
        for (Operation item : queued) {
            timers.arm(item);
        }
        LOG.info("The journal was recovered after the start: {} operation(s) were in flight and are closed; none is sent again", changed.size());
        dispatcher.open();
        return changed;
    }
}
