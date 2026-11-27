package io.github.jaeseongs95.opermand.ssh.exec;

import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.SshSettings;
import io.github.jaeseongs95.opermand.app.remote.StreamEnd;
import io.github.jaeseongs95.opermand.app.remote.StreamHandle;
import io.github.jaeseongs95.opermand.app.remote.StreamOpenResult;
import io.github.jaeseongs95.opermand.app.remote.StreamRequest;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.app.remote.UnconfirmedCause;
import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionReason;
import io.github.jaeseongs95.opermand.domain.operation.ExitStatus;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;
import io.github.jaeseongs95.opermand.ssh.session.ChannelLease;
import io.github.jaeseongs95.opermand.ssh.session.ChannelOpening;
import io.github.jaeseongs95.opermand.ssh.session.PermitReturn;
import io.github.jaeseongs95.opermand.ssh.sftp.TransferWatch;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.core.CoreModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;

/**
 * One command whose standard streams are kept open as binary streams (stage 3 design 2.2, cases A-816 to A-829), from the open of its channel to the {@link StreamEnd} of
 * its release. {@link MinaRemoteStreamExecutor} makes it. It shares the pieces of the exec adapter: the same channel ({@link SupervisedExecChannel}, which writes the
 * {@code exec} request only once the server confirmed the channel, and which can be abandoned before that), the same signals ({@link ChannelSignals}), the same rule about
 * what proves the end of a command (an exit status or an exit signal; the close of a channel proves nothing, D-0091) and the same wait for a channel open
 * ({@link ChannelOpening}).
 *
 * <p><b>Opening.</b> The request is sent at most once. A cancel or the time limit before the request was written gives certainty that the command never started; after
 * it, the stop procedure of the release is run and the answer is still {@code Refused(cause)} (never a lost transport). With {@code ExecReply.AWAITED} the open waits for
 * the target's answer to the request, and a refusal in it is the only proof that the command did not start; with {@code NOT_AWAITED} the open ends when the request was
 * written. A command never gets a terminal, whatever the profile says. The standard error is read by the channel and dropped, so it never blocks the command.
 *
 * <p><b>Releasing.</b> Steps spread over the stop grace: the end of the input and the signal INT at once, TERM after a third of the grace, KILL after two thirds, a
 * close of the channel after the grace, and a last resort after the channel's own close timeout. The result says what was seen: with no exit status or exit signal the
 * remote state is unknown and the cleanup is incomplete, however the channel closed.
 *
 * <p>The permit of the lease goes back when the channel closes, on every path. The state of a release is in atomics and in {@code timers}, which is guarded by itself.
 */
final class StreamSession implements StreamHandle, SupervisedExecChannel.Events {

    private static final Logger LOG = LoggerFactory.getLogger(StreamSession.class);
    private static final Duration CLOSE_MARGIN = Duration.ofMillis(250);
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(1);

    /** What the wait for the answer to the {@code exec} request found. */
    private enum Reply { ACCEPTED, REFUSED, SILENT, STOPPED, CLOSED }

    private final StreamRequest request;
    private final ChannelLease lease;
    private final ClientSession session;
    private final ChannelOpening opening;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ChannelSignals signals;
    private final SshSettings settings;
    private final SupervisedExecChannel channel;
    private final CompletableFuture<Boolean> answer = new CompletableFuture<>();
    private final CompletableFuture<StreamEnd> ended = new CompletableFuture<>();
    private final AtomicBoolean releasing = new AtomicBoolean();
    private final AtomicBoolean settling = new AtomicBoolean();
    private final List<ScheduledFuture<?>> timers = new ArrayList<>();
    private volatile boolean confirmed;
    private volatile boolean endSeenBeforeRelease;
    private volatile Duration closeTimeout = Duration.ofSeconds(5);

    StreamSession(StreamRequest request, ChannelLease lease, ChannelOpening opening, TaskScheduler scheduler, Clock clock, ChannelSignals signals, SshSettings settings) {
        this.request = request;
        this.lease = lease;
        this.session = lease.session();
        this.opening = opening;
        this.scheduler = scheduler;
        this.clock = clock;
        this.signals = signals;
        this.settings = settings;
        this.channel = new SupervisedExecChannel(request.command().commandLine(), this, false);
    }

    // ---- open ----------------------------------------------------------------------------------------------------------------------------------------------

    /** Opens the channel and sends the command; the caller has leased a permit and run the dispatch guard. The permit goes back with the channel. */
    StreamOpenResult open(ExecutionControl control) {
        CoreModuleProperties.REQUEST_EXEC_REPLY.set(channel, Boolean.valueOf(request.reply() == ExecReply.AWAITED));
        channel.setErr(OutputStream.nullOutputStream());
        try {
            session.getService(ConnectionService.class).registerChannel(channel);
        } catch (IOException | RuntimeException e) {
            LOG.debug("The session could not register an exec channel", e);
            return notSent();
        }
        closeTimeout = CoreModuleProperties.CHANNEL_CLOSE_TIMEOUT.getRequired(channel);
        new PermitReturn(lease).attachTo(channel);
        channel.addCloseFutureListener(closed -> onChannelClosed());
        try {
            return openRegistered(control);
        } catch (RuntimeException | Error e) {
            channel.close(true);
            throw e;
        }
    }

    private StreamOpenResult openRegistered(ExecutionControl control) {
        OpenFuture asked;
        try {
            asked = channel.open();
        } catch (IOException | RuntimeException e) {
            LOG.debug("The request to open an exec channel could not be written", e);
            channel.close(true);
            return notSent();
        }
        try (TransferWatch watch = new TransferWatch(scheduler, clock, request.limits().deadline(), control.cancel())) {
            return decide(opening.await(lease, true, channel, asked, watch, request.limits().stopGrace()), watch);
        }
    }

    private StreamOpenResult decide(ChannelOpening.Outcome outcome, TransferWatch watch) {
        return switch (outcome) {
            case ChannelOpening.Outcome.Opened opened -> afterOpen(watch);
            case ChannelOpening.Outcome.Rejected rejected -> {
                // A refused channel is dead: its permit goes back now, not when MINA gets to closing it.
                lease.close();
                yield refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
            }
            case ChannelOpening.Outcome.Stopped stopped -> {
                if (!channel.abandonIfNotSent()) {
                    // The request was written before the stop: the command may run, so it is stopped like a released one.
                    beginRelease();
                }
                yield refused(stopped.cause());
            }
            case ChannelOpening.Outcome.Lost lost -> {
                lease.close();
                yield channel.abandonIfNotSent() ? refused(TransferFailureCause.TRANSPORT_LOST) : unconfirmed();
            }
        };
    }

    /** The channel is open and the request is written. */
    private StreamOpenResult afterOpen(TransferWatch watch) {
        Reply reply = request.reply() == ExecReply.AWAITED ? awaitReply(watch) : Reply.ACCEPTED;
        return switch (reply) {
            case ACCEPTED -> writeInitialInput(watch);
            case REFUSED -> {
                channel.close(false);
                yield refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
            }
            case SILENT -> {
                // No answer in time: as for an exec (decision D3-06), it is unknown whether the command started.
                beginRelease();
                yield unconfirmed();
            }
            case STOPPED -> {
                beginRelease();
                yield refused(watch.fired().orElse(TransferFailureCause.CANCELLED));
            }
            case CLOSED -> unconfirmed();
        };
    }

    /** Waits for the target's answer to the {@code exec} request, for the owner's stop and for the time the answer may take. */
    private Reply awaitReply(TransferWatch watch) {
        CompletableFuture<Reply> decision = new CompletableFuture<>();
        answer.thenAccept(accepted -> decision.complete(accepted ? Reply.ACCEPTED : Reply.REFUSED));
        watch.interruptWith(() -> decision.complete(Reply.STOPPED));
        channel.addCloseFutureListener(closed -> decision.complete(Reply.CLOSED));
        ScheduledFuture<?> timer = scheduler.schedule(() -> decision.complete(Reply.SILENT), clock.instant().plus(settings.execReplyTimeout()));
        try {
            return decision.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.STOPPED;
        } catch (ExecutionException e) {
            throw new IllegalStateException("The wait for the answer to an exec request is only ever completed normally", e);
        } finally {
            timer.cancel(false);
        }
    }

    /** Writes the bytes of the command's standard input that the request carries (a Windows command travels this way); the input stays open for the owner. */
    private StreamOpenResult writeInitialInput(TransferWatch watch) {
        byte[] initial = request.command().stdin().bytes();
        if (initial.length > 0) {
            // A write that waits for the window of the target ends when the owner gives up: the channel is closed under it.
            watch.interruptWith(() -> channel.close(true));
            try {
                OutputStream input = channel.getInvertedIn();
                input.write(initial);
                input.flush();
            } catch (IOException e) {
                LOG.debug("The standard input of a stream could not be written", e);
                beginRelease();
                TransferFailureCause stop = watch.fired().orElse(null);
                return stop != null ? refused(stop) : unconfirmed();
            }
        }
        return new StreamOpenResult.Opened(this);
    }

    /** The channel was not registered or its open could not be written: nothing was sent. A dead session is a lost transport, a live one refused. */
    private StreamOpenResult notSent() {
        if (session.isOpen()) {
            lease.close();
            return refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
        }
        lease.sessionLost();
        lease.close();
        return refused(TransferFailureCause.TRANSPORT_LOST);
    }

    private static StreamOpenResult refused(TransferFailureCause cause) {
        return new StreamOpenResult.Refused(cause);
    }

    private static StreamOpenResult unconfirmed() {
        return new StreamOpenResult.Unconfirmed(new UnconfirmedCause.TransportLost());
    }

    // ---- events of the channel -----------------------------------------------------------------------------------------------------------------------------

    @Override
    public void channelConfirmed() {
        confirmed = true;
    }

    @Override
    public void requestWritten() {
        // Nothing to do: the open ends right after the request was written.
    }

    @Override
    public void replyReceived(boolean accepted) {
        answer.complete(accepted);
    }

    @Override
    public void outputArrived() {
        // Nothing to do: the bytes go to the reader of the output stream.
    }

    /** The permit goes back with the channel; a release that waits for the close is finished. */
    private void onChannelClosed() {
        lease.close();
        if (releasing.get()) {
            settle();
        }
    }

    // ---- the streams ---------------------------------------------------------------------------------------------------------------------------------------

    @Override
    public InputStream output() {
        return channel.getInvertedOut();
    }

    @Override
    public OutputStream input() {
        return channel.getInvertedIn();
    }

    // ---- release -------------------------------------------------------------------------------------------------------------------------------------------

    @Override
    public StreamEnd release() {
        CompletableFuture<StreamEnd> result = beginRelease();
        Duration limit = request.limits().stopGrace().plus(closeTimeout).plus(CLOSE_MARGIN).plus(WAIT_MARGIN);
        try {
            return result.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOG.warn("The release of a stream did not settle in time; it is settled with what is known", e);
            settle();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("The wait for the release of a stream was interrupted; it is settled with what is known", e);
            settle();
        } catch (ExecutionException e) {
            throw new IllegalStateException("The release of a stream is only ever completed normally", e);
        }
        return result.join();
    }

    /** Starts the stop (first call only) without waiting for it. */
    private CompletableFuture<StreamEnd> beginRelease() {
        if (releasing.compareAndSet(false, true)) {
            startStop();
        }
        return ended;
    }

    private void startStop() {
        endSeenBeforeRelease = !(channel.exit() instanceof ExitStatus.NoExit);
        if (channel.isClosed()) {
            settle();
            return;
        }
        Duration grace = request.limits().stopGrace();
        long third = Math.max(1, grace.toMillis() / 3);
        // The first step does not run on the caller: closing the input waits for a writer that is blocked on the window of the target.
        schedule(Duration.ZERO, this::stopFirstStep);
        if (confirmed && !endSeenBeforeRelease) {
            schedule(Duration.ofMillis(third), () -> signalLater(ChannelSignals.TERMINATE));
            schedule(Duration.ofMillis(2 * third), () -> signalLater(ChannelSignals.KILL));
        }
        schedule(grace, () -> channel.close(false));
        schedule(grace.plus(closeTimeout).plus(CLOSE_MARGIN), this::settle);
    }

    /** The end of the input (the cue for a command to end by itself) and INT. */
    private void stopFirstStep() {
        closeQuietly(channel.getInvertedIn());
        if (confirmed && !endSeenBeforeRelease) {
            signals.send(channel, ChannelSignals.INTERRUPT);
        }
    }

    private void signalLater(String name) {
        if (!ended.isDone() && channel.isOpen()) {
            signals.send(channel, name);
        }
    }

    /** Fixes the result (once): reads what the server said, forces the channel closed if it is not, and gives the permit back. */
    private void settle() {
        if (!settling.compareAndSet(false, true)) {
            return;
        }
        synchronized (timers) {
            for (ScheduledFuture<?> timer : timers) {
                timer.cancel(false);
            }
        }
        boolean channelClosed = channel.isClosed();
        if (!channelClosed) {
            channel.close(true);
        }
        closeQuietly(channel.getInvertedOut());
        ExitStatus exit = channel.exit();
        boolean exited = !(exit instanceof ExitStatus.NoExit);
        boolean sessionGone = !session.isOpen();
        ExecutionReason reason;
        if (endSeenBeforeRelease) {
            reason = ExecutionReason.EXIT;
        } else if (sessionGone && !exited) {
            reason = ExecutionReason.TRANSPORT_LOST;
        } else {
            reason = ExecutionReason.CANCEL;
        }
        // A channel that closed proves the cleanup only when the end of the command was seen, or when the session is gone (nothing is left to close).
        CleanupState cleanup = channelClosed && (exited || sessionGone) ? CleanupState.COMPLETE : CleanupState.INCOMPLETE;
        if (sessionGone && !exited) {
            lease.sessionLost();
        }
        lease.close();
        ended.complete(new StreamEnd(reason, exited ? RemoteState.EXITED : RemoteState.UNKNOWN, exit, cleanup));
    }

    private void schedule(Duration delay, Runnable action) {
        ScheduledFuture<?> timer = scheduler.schedule(action, clock.instant().plus(delay));
        synchronized (timers) {
            timers.add(timer);
        }
    }

    private static void closeQuietly(Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException e) {
            LOG.debug("A stream of a command did not close cleanly; the release goes on", e);
        }
    }
}
