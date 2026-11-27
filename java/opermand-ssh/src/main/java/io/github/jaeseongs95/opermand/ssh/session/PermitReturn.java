package io.github.jaeseongs95.opermand.ssh.session;

import java.util.Objects;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.SshFutureListener;

/**
 * Gives the channel permit of a {@link ChannelLease} back when the channel it was leased for is closed, whatever closed it (the owner, the server, a refused open, a lost
 * session): the way a slot of the session pool is returned on every path (defect D-0068), so a waiter of the pool never waits for a channel that is gone.
 *
 * <p>It listens twice on purpose. The channel's own listener runs before the listeners of the session and of the client (MINA calls the channel's first), so anyone who
 * learns of the close from the session finds the permit already back. The close future also fires for a channel whose open failed, which has no close event of its own.
 * Giving the permit back twice does nothing ({@link ChannelLease#close}).
 */
public final class PermitReturn implements ChannelListener, SshFutureListener<CloseFuture> {

    private final ChannelLease lease;

    public PermitReturn(ChannelLease lease) {
        this.lease = Objects.requireNonNull(lease, "lease");
    }

    /** From now on the permit goes back when the channel closes. */
    public void attachTo(ClientChannel channel) {
        channel.addChannelListener(this);
        channel.addCloseFutureListener(this);
    }

    /** The permit no longer goes back with the channel: its owner takes it over. */
    public void detachFrom(ClientChannel channel) {
        channel.removeChannelListener(this);
        channel.removeCloseFutureListener(this);
    }

    @Override
    public void channelClosed(Channel channel, Throwable reason) {
        lease.close();
    }

    @Override
    public void operationComplete(CloseFuture future) {
        lease.close();
    }
}
