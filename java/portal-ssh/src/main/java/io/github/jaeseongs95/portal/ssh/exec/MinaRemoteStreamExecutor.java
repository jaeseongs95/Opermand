package io.github.jaeseongs95.portal.ssh.exec;

import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.RemoteStreamExecutor;
import io.github.jaeseongs95.portal.app.remote.SshSettings;
import io.github.jaeseongs95.portal.app.remote.StreamOpenResult;
import io.github.jaeseongs95.portal.app.remote.StreamRequest;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.ssh.session.AcquireResult;
import io.github.jaeseongs95.portal.ssh.session.ChannelLease;
import io.github.jaeseongs95.portal.ssh.session.ChannelOpening;
import io.github.jaeseongs95.portal.ssh.session.SshSessionPool;
import java.time.Clock;
import java.util.Objects;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

/**
 * The {@link RemoteStreamExecutor} of the SSH adapter on Apache MINA (stage 3 design 2.2; cases A-816 to A-829): a command without a terminal whose standard streams the
 * owner reads and writes as binary streams, for the helpers of a GUI lease. Like {@link MinaRemoteExecutor} it gets its session from the {@link SshSessionPool} (the only
 * place a connection is made, and only before anything is sent) and asks the owner's {@code DispatchGuard} once more before the channel is opened; the rest is a
 * {@link StreamSession}, which owns the channel until the owner releases the handle.
 *
 * <p>The answer is a value and every way of not starting is a value of its own: no session {@code Refused(CONNECT_FAILED)}, a full session or a refusal of the server
 * {@code Refused(CHANNEL_OPEN_FAILED)}, the time limit {@code Refused(DEADLINE)}, the owner's cancel {@code Refused(CANCELLED)}, a session lost after the request
 * {@code Unconfirmed(TransportLost)}. The request is sent at most once and nothing here retries it (decision D3-08). A cancel or a time limit never drops a live session.
 */
@Component
public final class MinaRemoteStreamExecutor implements RemoteStreamExecutor {

    private final SshSessionPool pool;
    private final ChannelOpening opening;
    private final TaskScheduler scheduler;
    private final Clock clock;
    private final ChannelSignals signals;
    private final SshSettings settings;

    public MinaRemoteStreamExecutor(SshSessionPool pool, ChannelOpening opening, TaskScheduler scheduler, Clock clock, ChannelSignals signals, SshSettings settings) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.opening = Objects.requireNonNull(opening, "opening");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public StreamOpenResult open(StreamRequest request, ExecutionControl control) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(control, "control");
        if (control.cancel().requested()) {
            return new StreamOpenResult.Refused(TransferFailureCause.CANCELLED);
        }
        return switch (pool.acquire(request.target().profile(), control.cancel())) {
            case AcquireResult.Cancelled cancelled -> new StreamOpenResult.Refused(TransferFailureCause.CANCELLED);
            case AcquireResult.Failed failed -> new StreamOpenResult.Refused(TransferFailureCause.CONNECT_FAILED);
            case AcquireResult.NoChannelPermit noPermit -> new StreamOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED);
            case AcquireResult.Leased leased -> openOn(request, control, leased.lease());
        };
    }

    private StreamOpenResult openOn(StreamRequest request, ExecutionControl control, ChannelLease lease) {
        try {
            if (control.cancel().requested()) {
                lease.close();
                return new StreamOpenResult.Refused(TransferFailureCause.CANCELLED);
            }
            // The last check before anything is sent (P3): a target changed while the connection was being made is never reached with the old approval.
            control.guard().beforeDispatch();
            return new StreamSession(request, lease, opening, scheduler, clock, signals, settings).open(control);
        } catch (RuntimeException | Error e) {
            // The guard's refusal, or a failure of the adapter itself: the permit goes back and the exception goes on (closing it again does nothing).
            lease.close();
            throw e;
        }
    }
}
