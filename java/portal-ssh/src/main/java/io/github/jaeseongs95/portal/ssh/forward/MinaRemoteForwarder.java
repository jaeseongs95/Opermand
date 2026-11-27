package io.github.jaeseongs95.portal.ssh.forward;

import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.ForwardOpenResult;
import io.github.jaeseongs95.portal.app.remote.ForwardRequest;
import io.github.jaeseongs95.portal.app.remote.RemoteForwarder;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.app.remote.UnconfirmedCause;
import io.github.jaeseongs95.portal.ssh.session.AcquireResult;
import io.github.jaeseongs95.portal.ssh.session.ChannelLease;
import io.github.jaeseongs95.portal.ssh.session.ChannelOpening;
import io.github.jaeseongs95.portal.ssh.session.PermitReturn;
import io.github.jaeseongs95.portal.ssh.session.SshSessionPool;
import io.github.jaeseongs95.portal.ssh.sftp.TransferWatch;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Clock;
import java.util.Objects;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The {@link RemoteForwarder} of the SSH adapter on Apache MINA (stage 3 design 2.2; defects D-0021, D-0022). It leases a channel permit from the {@link SshSessionPool}
 * (the only place a connection is made, and only before anything is sent), asks the owner's {@code DispatchGuard} once more, binds a loopback listener, opens the
 * {@code direct-tcpip} channel and waits for the server's answer under the owner's cancel and the time limit of the request ({@link ChannelOpening}). The answer is a
 * value, and every way of not opening is a value of its own:
 * <ul>
 * <li>no session: {@code Refused(CONNECT_FAILED)}; a session that is full, a server that refuses the channel, or a target that refuses the connection:
 * {@code Refused(CHANNEL_OPEN_FAILED)}; a session found dead before the request: {@code Refused(TRANSPORT_LOST)};</li>
 * <li>the time limit: {@code Refused(DEADLINE)}; the owner's cancel: {@code Refused(CANCELLED)}. Neither drops the session, and a channel that the target opens later is
 * closed ({@link ChannelOpening});</li>
 * <li>a session lost after the request was sent: {@code Unconfirmed(TransportLost)}, never an answer for a cancel or a time limit.</li>
 * </ul>
 * The permit of the lease goes back on every path: with the channel when the open failed or was given up, with {@link LocalForward#release()} when it opened. The
 * request is sent at most once; nothing here retries.
 */
@Component
public final class MinaRemoteForwarder implements RemoteForwarder {

    private static final Logger LOG = LoggerFactory.getLogger(MinaRemoteForwarder.class);
    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    private final SshSessionPool pool;
    private final ChannelOpening opening;
    private final TaskScheduler scheduler;
    private final TaskExecutor executor;
    private final Clock clock;

    public MinaRemoteForwarder(SshSessionPool pool, ChannelOpening opening, TaskScheduler scheduler, TaskExecutor executor, Clock clock) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.opening = Objects.requireNonNull(opening, "opening");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ForwardOpenResult open(ForwardRequest request, ExecutionControl control) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(control, "control");
        if (control.cancel().requested()) {
            return refused(TransferFailureCause.CANCELLED);
        }
        return switch (pool.acquire(request.target().profile(), control.cancel())) {
            case AcquireResult.Cancelled cancelled -> refused(TransferFailureCause.CANCELLED);
            case AcquireResult.Failed failed -> refused(TransferFailureCause.CONNECT_FAILED);
            case AcquireResult.NoChannelPermit noPermit -> refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
            case AcquireResult.Leased leased -> openOn(request, control, leased.lease());
        };
    }

    private ForwardOpenResult openOn(ForwardRequest request, ExecutionControl control, ChannelLease lease) {
        ServerSocket listener = null;
        ChannelDirectTcpip channel = null;
        boolean handedOver = false;
        try {
            if (control.cancel().requested()) {
                return refused(TransferFailureCause.CANCELLED);
            }
            // The last check before anything is sent (P3): a target changed while the connection was being made is never reached with the old approval.
            control.guard().beforeDispatch();
            try {
                listener = new ServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK));
            } catch (IOException e) {
                LOG.warn("The loopback listener of a forward could not be opened", e);
                return refused(TransferFailureCause.LOCAL_IO_FAILED);
            }
            ClientSession session = lease.session();
            SshdSocketAddress from = new SshdSocketAddress(listener.getInetAddress().getHostAddress(), 0);
            try {
                channel = session.createDirectTcpipChannel(from, new SshdSocketAddress(request.host(), request.port()));
            } catch (IOException | RuntimeException e) {
                LOG.debug("The session could not register a direct-tcpip channel", e);
                return notRegistered(session, lease);
            }
            // The permit goes back with the channel, whatever becomes of it; a forward that opened takes the permit over (below).
            PermitReturn freeThePermit = new PermitReturn(lease);
            freeThePermit.attachTo(channel);
            OpenFuture asked;
            try {
                asked = channel.open();
            } catch (IOException | RuntimeException e) {
                LOG.debug("The request to open a direct-tcpip channel could not be written", e);
                channel.close(true);
                return notRegistered(session, lease);
            }
            ChannelOpening.Outcome outcome;
            try (TransferWatch watch = new TransferWatch(scheduler, clock, request.limits().deadline(), control.cancel())) {
                outcome = opening.await(lease, false, channel, asked, watch, request.limits().stopGrace());
            }
            return switch (outcome) {
                case ChannelOpening.Outcome.Opened opened -> {
                    freeThePermit.detachFrom(channel);
                    LocalForward forward = new LocalForward(lease, channel, listener, request.limits().stopGrace(), executor);
                    try {
                        forward.start();
                    } catch (RuntimeException e) {
                        LOG.warn("The tasks of a forward could not be started", e);
                        forward.release();
                        yield refused(TransferFailureCause.LOCAL_IO_FAILED);
                    }
                    handedOver = true;
                    yield new ForwardOpenResult.Opened(forward);
                }
                case ChannelOpening.Outcome.Rejected rejected -> {
                    // A refused channel is dead: its permit goes back now, not when MINA gets to closing it.
                    lease.close();
                    yield refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
                }
                // A channel that is given up stays alive until the server answers or the grace passes, and holds its permit until then.
                case ChannelOpening.Outcome.Stopped stopped -> refused(stopped.cause());
                case ChannelOpening.Outcome.Lost lost -> {
                    // The session is gone and so is the channel. The request was sent: the target may have connected before that.
                    lease.close();
                    yield new ForwardOpenResult.Unconfirmed(new UnconfirmedCause.TransportLost());
                }
            };
        } catch (RuntimeException | Error e) {
            // The guard's refusal, or a failure of the adapter itself: nothing stays open, and the exception goes on.
            if (channel == null) {
                lease.close();
            } else {
                channel.close(true);
            }
            throw e;
        } finally {
            if (!handedOver) {
                closeQuietly(listener);
                if (channel == null) {
                    lease.close();
                }
            }
        }
    }

    /** The channel could not be registered or its request not written: a dead session is a lost transport, a live one refused. Nothing was sent. */
    private ForwardOpenResult notRegistered(ClientSession session, ChannelLease lease) {
        if (session.isOpen()) {
            return refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
        }
        lease.sessionLost();
        return refused(TransferFailureCause.TRANSPORT_LOST);
    }

    private static ForwardOpenResult refused(TransferFailureCause cause) {
        return new ForwardOpenResult.Refused(cause);
    }

    private static void closeQuietly(ServerSocket listener) {
        if (listener == null) {
            return;
        }
        try {
            listener.close();
        } catch (IOException e) {
            LOG.debug("The loopback listener of a forward did not close cleanly; the close goes on", e);
        }
    }
}
