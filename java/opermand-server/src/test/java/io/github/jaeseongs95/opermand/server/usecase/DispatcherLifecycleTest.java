package io.github.jaeseongs95.opermand.server.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import io.github.jaeseongs95.opermand.app.event.OperationEvents;
import io.github.jaeseongs95.opermand.app.execution.DispatchChecks;
import io.github.jaeseongs95.opermand.app.execution.ExecutionDispatcher;
import io.github.jaeseongs95.opermand.app.execution.OperationRunner;
import io.github.jaeseongs95.opermand.app.history.ExecutionTimelineStore;
import io.github.jaeseongs95.opermand.app.journal.ClaimStore;
import io.github.jaeseongs95.opermand.app.journal.OperationJournal;
import io.github.jaeseongs95.opermand.app.journal.OperationQueueView;
import io.github.jaeseongs95.opermand.app.journal.RestartRecovery;
import io.github.jaeseongs95.opermand.app.operation.ExpiryTimers;
import io.github.jaeseongs95.opermand.app.operation.OperationExpiry;
import io.github.jaeseongs95.opermand.app.operation.OperationWrites;
import io.github.jaeseongs95.opermand.app.operation.OutcomeAudit;
import io.github.jaeseongs95.opermand.app.operation.RunningExecutions;
import io.github.jaeseongs95.opermand.app.operation.TerminalNotifier;
import io.github.jaeseongs95.opermand.app.recovery.SettlementRecovery;
import io.github.jaeseongs95.opermand.app.recovery.StartupRecovery;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionReport;
import io.github.jaeseongs95.opermand.domain.operation.Operation;
import io.github.jaeseongs95.opermand.domain.operation.OperationKind;
import io.github.jaeseongs95.opermand.domain.schedule.ConcurrencyLimits;
import io.github.jaeseongs95.opermand.domain.schedule.OperationScheduler;

/**
 * The life of the dispatcher itself: it is closed until the recovery of the journal has finished (decision on the recovery moment, S3-UC-05), and the runners are keyed
 * by the kind they run, so two runners for one kind stop the start (S3-UC-01). The dispatchers here are built by hand from the real beans of the context, with an
 * executor that only counts, so that the order of the steps is what the test looks at.
 */
@UseCaseTest
class DispatcherLifecycleTest {

    private final ApplicationContext context;

    DispatcherLifecycleTest(ApplicationContext context) {
        this.context = context;
    }

    private ExecutionDispatcher dispatcher(TaskExecutor executor, List<OperationRunner> runners) {
        return new ExecutionDispatcher(context.getBean(OperationJournal.class), context.getBean(OperationQueueView.class), context.getBean(ClaimStore.class),
            context.getBean(OperationScheduler.class), context.getBean(ConcurrencyLimits.class), context.getBean(OperationExpiry.class),
            context.getBean(OperationWrites.class), context.getBean(OutcomeAudit.class), context.getBean(OperationEvents.class), context.getBean(RunningExecutions.class),
            context.getBean(DispatchChecks.class), context.getBean(ExecutionTimelineStore.class), executor, context.getBean(Clock.class), runners);
    }

    @Test
    @DisplayName("S3-UC-05 the dispatcher does nothing until it is opened, the recovery of the journal runs first, and the first dispatch comes after it")
    void theRecoveryFinishesBeforeTheFirstDispatch() {
        List<String> order = new CopyOnWriteArrayList<>();
        ExecutionDispatcher dispatcher = dispatcher(task -> order.add("dispatch"), List.of());
        RestartRecovery real = context.getBean(RestartRecovery.class);
        RestartRecovery recording = at -> {
            order.add("recovered");
            return real.recoverAfterRestart(at);
        };

        dispatcher.wake();
        assertThat(order).as("a wake before the recovery drains nothing").isEmpty();

        new StartupRecovery(recording, context.getBean(OperationQueueView.class), context.getBean(OperationEvents.class), context.getBean(TerminalNotifier.class),
            context.getBean(SettlementRecovery.class), context.getBean(ExpiryTimers.class), dispatcher, context.getBean(ConcurrencyLimits.class), context.getBean(Clock.class)).run();

        assertThat(order).containsExactly("recovered", "dispatch");
    }

    @Test
    @DisplayName("S3-UC-05 a recovery that fails leaves the dispatcher closed: nothing is dispatched")
    void aFailedRecoveryKeepsTheDispatcherClosed() {
        List<String> order = new CopyOnWriteArrayList<>();
        ExecutionDispatcher dispatcher = dispatcher(task -> order.add("dispatch"), List.of());
        RestartRecovery failing = at -> {
            throw new IllegalStateException("the journal is not writable");
        };
        StartupRecovery startup = new StartupRecovery(failing, context.getBean(OperationQueueView.class), context.getBean(OperationEvents.class),
            context.getBean(TerminalNotifier.class), context.getBean(SettlementRecovery.class), context.getBean(ExpiryTimers.class), dispatcher,
            context.getBean(ConcurrencyLimits.class), context.getBean(Clock.class));

        assertThatThrownBy(startup::run).isInstanceOf(RuntimeException.class);
        dispatcher.wake();

        assertThat(order).isEmpty();
    }

    @Test
    @DisplayName("S-D0158-01 D-0158 an executor that refuses the drain does not leave the dispatcher busy for good: the next wake is handed to the executor again")
    void aRefusedDrainDoesNotStopTheDispatcherForGood() {
        List<String> order = new CopyOnWriteArrayList<>();
        AtomicInteger offers = new AtomicInteger();
        TaskExecutor refusingOnce = task -> {
            if (offers.incrementAndGet() == 1) {
                throw new TaskRejectedException("the pool is shut down");
            }
            order.add("dispatch");
        };
        ExecutionDispatcher dispatcher = dispatcher(refusingOnce, List.of());

        dispatcher.open();
        assertThat(offers).as("the open wake was offered and refused").hasValue(1);
        dispatcher.wake();

        assertThat(order).as("the second wake reached the executor").containsExactly("dispatch");
    }

    @Test
    @DisplayName("S3-UC-01 two runners for one kind of operation stop the start with IllegalStateException")
    void twoRunnersForOneKindAreRefused() {
        OperationRunner first = new IdleRunner();
        OperationRunner second = new IdleRunner();
        assertThatThrownBy(() -> dispatcher(task -> { }, List.of(first, second))).isInstanceOf(IllegalStateException.class).hasMessageContaining("COMMAND");
    }

    /** A runner that is never asked to run; it only says which kind it is for. */
    private static final class IdleRunner implements OperationRunner {

        @Override
        public OperationKind kind() {
            return OperationKind.COMMAND;
        }

        @Override
        public ExecutionReport run(Operation operation, ExecutionControl control) {
            throw new IllegalStateException("not run in this test");
        }
    }
}
