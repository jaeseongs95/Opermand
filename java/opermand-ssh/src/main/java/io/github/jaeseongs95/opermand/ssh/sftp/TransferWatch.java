package io.github.jaeseongs95.opermand.ssh.sftp;

import io.github.jaeseongs95.opermand.app.remote.CancelToken;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sshd.client.channel.ClientChannel;
import org.springframework.scheduling.TaskScheduler;

/**
 * Watches one remote request (a file transfer, and the wait of a channel open in {@code ChannelOpening}) for the two things that stop it from outside: the time limit and
 * the owner's cancel. Both are events, not a poll: the deadline is a task of
 * the injected {@link TaskScheduler}, the cancel is a subscription to {@link CancelToken#whenRequested()}. When one fires, the watch records which, and runs the
 * interrupt (it closes the SFTP channel, so a call that blocks on the network fails at once). The first cause wins and stays.
 *
 * <p>The watch can be disarmed. The adapter does that when the replacement of a file begins: a few steps that must run to their end, so that an owner's cancel does not
 * leave a half-replaced file. A cancel is a request, and the result of the transfer says what became of it.
 *
 * <p>One watch serves one transfer and is closed with it. It is thread safe: the scheduler's thread and the cancelling thread call into it while the transfer thread
 * reads it.
 */
public final class TransferWatch implements AutoCloseable {

    private final TaskScheduler scheduler;
    private final Clock clock;
    private final Instant deadlineAt;
    private final CancelToken cancel;
    private final AtomicReference<TransferFailureCause> fired = new AtomicReference<>();
    private final ScheduledFuture<?> deadlineTask;
    private volatile Runnable interrupt = () -> { };
    private volatile boolean armed = true;

    public TransferWatch(TaskScheduler scheduler, Clock clock, Duration deadline, CancelToken cancel) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(deadline, "deadline");
        this.cancel = Objects.requireNonNull(cancel, "cancel");
        this.deadlineAt = clock.instant().plus(deadline);
        this.deadlineTask = scheduler.schedule(() -> fire(TransferFailureCause.DEADLINE), deadlineAt);
        cancel.whenRequested().thenRun(() -> fire(TransferFailureCause.CANCELLED));
    }

    /** Remaining time until the transfer deadline, clamped to zero once reached. */
    Duration remaining() {
        Duration left = Duration.between(clock.instant(), deadlineAt);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /**
     * Schedules a local forced close of {@code channel} after {@code grace} if the server has not completed the graceful close by then (the same grace-then-force rule
     * {@code ChannelOpening} uses for abandoned channels). The scheduled task is cancelled as soon as the channel closes.
     */
    void forceCloseAfterGrace(ClientChannel channel, Duration grace) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(grace, "grace");
        if (channel.isClosed()) {
            return;
        }
        ScheduledFuture<?> force = scheduler.schedule(() -> channel.close(true), clock.instant().plus(grace));
        channel.addCloseFutureListener(closed -> force.cancel(false));
        if (channel.isClosed()) {
            force.cancel(false);
        }
    }

    /** What to do when the transfer is stopped from outside; runs at once when it already was. */
    public void interruptWith(Runnable action) {
        this.interrupt = Objects.requireNonNull(action, "action");
        if (fired.get() != null && armed) {
            action.run();
        }
    }

    /** Why the transfer was stopped from outside, if it was. */
    public Optional<TransferFailureCause> fired() {
        return Optional.ofNullable(fired.get());
    }

    /** The checkpoint of a loop: the cause when the transfer was stopped, also when the cancel was requested a moment ago and the event has not run yet. */
    public Optional<TransferFailureCause> stopRequested() {
        if (armed && fired.get() == null && cancel.requested()) {
            fire(TransferFailureCause.CANCELLED);
        }
        return fired();
    }

    /** From now on nothing stops the transfer from outside. */
    public void disarm() {
        armed = false;
        deadlineTask.cancel(false);
    }

    @Override
    public void close() {
        disarm();
    }

    private void fire(TransferFailureCause cause) {
        if (armed && fired.compareAndSet(null, cause)) {
            interrupt.run();
        }
    }
}
