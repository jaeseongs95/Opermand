package io.github.jaeseongs95.opermand.ssh.session;

import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.ssh.sftp.TransferWatch;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.exception.SshChannelOpenException;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * Waits for the answer of the server to one channel open and decides what becomes of a channel whose open the owner gave up (stage 3 design 3.2 P2, the late channel of
 * defect D-0021, case RC-A-718). It is the one place the channels that are opened without a command ({@code direct-tcpip}) and the exec channel of a stream wait for their
 * open, so the waiting, the cancel and the time limit are written once.
 *
 * <p>The wait ends with the first of four events, all of them events and not polls: the server answers (the channel is open, or the server refused it), the
 * {@link TransferWatch} fires (the owner's cancel or the time limit), or the session closes. Then:
 * <ul>
 * <li><b>The owner gave up.</b> The channel is not closed at once: a channel that is closed before the server confirmed it is forgotten by the client, and the confirmation
 * that comes later would leave the channel open on the server (MINA ignores a confirmation for an unknown channel). The open is cancelled instead
 * ({@code OpenFuture.cancel}), which makes MINA close the channel, with a close that the server gets, as soon as it is confirmed. If no close is seen within {@code grace},
 * the channel is closed locally (the server may keep it until the session ends) and the permit of the lease is freed by that close.</li>
 * <li><b>The session closed.</b> The pending open is cancelled and the channel closed, so the permit of the lease is freed.</li>
 * </ul>
 * The session stays in the pool in every case but a closed one: giving up an open is not a reason to drop a live session.
 */
@Component
public final class ChannelOpening {

    /** What the wait found. */
    public sealed interface Outcome permits Outcome.Opened, Outcome.Rejected, Outcome.Stopped, Outcome.Lost {

        /** The server confirmed the channel (and, for an exec channel, the request was written). */
        record Opened() implements Outcome {
        }

        /** The server refused to open the channel (for a {@code direct-tcpip} channel: the target refused the connection, or forwarding is not allowed). */
        record Rejected() implements Outcome {
        }

        /**
         * The owner cancelled or the time limit passed before the server answered; the channel is being given up.
         *
         * @param cause {@link TransferFailureCause#CANCELLED} or {@link TransferFailureCause#DEADLINE}
         */
        record Stopped(TransferFailureCause cause) implements Outcome {
            public Stopped {
                Objects.requireNonNull(cause, "cause");
            }
        }

        /** The session closed, or the channel failed for a reason other than a refusal, before the server answered. */
        record Lost() implements Outcome {
        }
    }

    private final TaskScheduler scheduler;
    private final Clock clock;

    public ChannelOpening(TaskScheduler scheduler, Clock clock) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Blocks until the open is answered or given up.
     *
     * @param lease the lease the channel was opened on; it learns the answer of the server (accepted or refused) before the channel can be closed, so the pool counts the
     *     refusals in a row (decision D3-08)
     * @param refusalIsTheSessions true when a refusal says something about the session (a session channel: the server is at its limit of sessions); false when it says
     *     something about the far end (a {@code direct-tcpip} channel: the target refused the connection), which is no reason to count it against the session
     * @param channel the registered channel whose open request was written (or is being written)
     * @param opening the future of that open
     * @param watch the watch of the owner's cancel and the time limit, armed by the caller
     * @param grace how long a channel that was given up may stay before it is closed locally
     * @return what the wait found; for {@link Outcome.Stopped} and {@link Outcome.Lost} the channel is already being closed
     */
    public Outcome await(ChannelLease lease, boolean refusalIsTheSessions, ClientChannel channel, OpenFuture opening, TransferWatch watch, Duration grace) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(grace, "grace");
        ClientSession session = lease.session();
        CompletableFuture<Outcome> first = new CompletableFuture<>();
        SessionListener closedSession = new SessionListener() {
            @Override
            public void sessionClosed(Session closed) {
                first.complete(new Outcome.Lost());
            }
        };
        session.addSessionListener(closedSession);
        Outcome outcome;
        try {
            opening.addListener(done -> first.complete(answered(lease, refusalIsTheSessions, done)));
            watch.interruptWith(() -> watch.fired().ifPresent(cause -> first.complete(new Outcome.Stopped(cause))));
            if (!session.isOpen()) {
                first.complete(new Outcome.Lost());
            }
            outcome = first.get();
        } catch (InterruptedException e) {
            // The thread of the caller was interrupted: that is a cancel of the owner's, and the open is given up like one.
            Thread.currentThread().interrupt();
            outcome = new Outcome.Stopped(TransferFailureCause.CANCELLED);
        } catch (ExecutionException e) {
            throw new IllegalStateException("The wait for a channel open is only ever completed normally", e);
        } finally {
            session.removeSessionListener(closedSession);
        }
        if (outcome instanceof Outcome.Stopped) {
            giveUp(channel, opening, grace);
        } else if (outcome instanceof Outcome.Lost) {
            if (!session.isOpen()) {
                lease.sessionLost();
            }
            opening.cancel();
            channel.close(true);
        }
        return outcome;
    }

    /** What the server did; called on a MINA thread, before the channel of a refused open is closed, so the lease counts the refusal before its permit goes back. */
    private static Outcome answered(ChannelLease lease, boolean refusalIsTheSessions, OpenFuture done) {
        if (done.isOpened()) {
            lease.openAccepted();
            return new Outcome.Opened();
        }
        if (isRefusal(done.getException())) {
            if (refusalIsTheSessions) {
                lease.openRejected();
            }
            return new Outcome.Rejected();
        }
        return new Outcome.Lost();
    }

    private static boolean isRefusal(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SshChannelOpenException) {
                return true;
            }
        }
        return false;
    }

    private void giveUp(ClientChannel channel, OpenFuture opening, Duration grace) {
        // MINA closes a channel that is confirmed after the cancel (AbstractClientChannel.handleOpenSuccess), with a close the server receives.
        opening.cancel();
        if (opening.isOpened()) {
            // The confirmation was handled before the cancel took effect: the channel is open and nobody holds it, so it is closed here.
            channel.close(false);
        }
        ScheduledFuture<?> force = scheduler.schedule(() -> channel.close(true), clock.instant().plus(grace));
        channel.addCloseFutureListener(closed -> force.cancel(false));
    }
}
