package io.github.jaeseongs95.portal.ssh.forward;

import io.github.jaeseongs95.portal.app.remote.ForwardHandle;
import io.github.jaeseongs95.portal.domain.operation.CleanupState;
import io.github.jaeseongs95.portal.ssh.session.ChannelLease;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

/**
 * One open local forward (stage 3 design 2.2, defect D-0021): a loopback listener, the {@code direct-tcpip} channel that was opened before the forward was answered, and the
 * two pumps that carry the bytes of the first client between them. It owns everything it started, and {@link #release()} closes all of it and says whether that is confirmed.
 *
 * <p><b>One connection.</b> The listener accepts the first client and closes itself, so no other process of this machine can connect to the forward afterwards. When either
 * end of the connection closes, both are closed. The forward then stays until the owner releases it: the permit of the session lease is held until then.
 *
 * <p><b>What a close confirms.</b> The channel is closed with the close of the SSH protocol and the close is awaited for the stop grace; a close that the server did not
 * answer in that time is forced locally and reported as {@link CleanupState#INCOMPLETE}, because the server may still hold its side of the connection. The listener and the
 * threads (the accept task and the two pumps run on the injected {@link TaskExecutor}, never on a thread of this class) are awaited as well. A session that closes by itself
 * releases the forward on the executor, so no listener and no thread outlives the session.
 *
 * <p>The state of the close is guarded by {@code lock}; the calls into MINA and the sockets are made outside it.
 */
final class LocalForward implements ForwardHandle {

    private static final Logger LOG = LoggerFactory.getLogger(LocalForward.class);
    private static final int BUFFER_BYTES = 16 * 1024;

    private final ChannelLease lease;
    private final ClientSession session;
    private final ChannelDirectTcpip channel;
    private final ServerSocket listener;
    private final Duration grace;
    private final TaskExecutor executor;
    private final Object lock = new Object();
    private final List<CompletableFuture<Void>> tasks = new CopyOnWriteArrayList<>();
    private final CompletableFuture<CleanupState> released = new CompletableFuture<>();
    private final SessionListener lostSession = new SessionListener() {
        @Override
        public void sessionClosed(Session closed) {
            // On a MINA thread: the release blocks, so it runs on the executor.
            try {
                executor.execute(LocalForward.this::release);
            } catch (RuntimeException e) {
                LOG.warn("A forward could not be released after its session closed; the owner's release will do it", e);
            }
        }
    };
    // guarded by lock
    private boolean closing;
    private Socket client;

    /**
     * @param lease the permit on the session; closed when the forward is released
     * @param channel the open channel that carries the first connection
     * @param listener the bound loopback listener
     * @param grace how long a close and the end of the tasks are awaited
     */
    LocalForward(ChannelLease lease, ChannelDirectTcpip channel, ServerSocket listener, Duration grace, TaskExecutor executor) {
        this.lease = Objects.requireNonNull(lease, "lease");
        this.session = lease.session();
        this.channel = Objects.requireNonNull(channel, "channel");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.grace = Objects.requireNonNull(grace, "grace");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Starts the accept task and watches the session. Called once, by the forwarder, before the handle is given out.
     *
     * @throws org.springframework.core.task.TaskRejectedException when the executor takes no task; the caller releases the forward
     */
    void start() {
        session.addSessionListener(lostSession);
        spawn(this::acceptOne);
        if (!session.isOpen()) {
            // The session closed before the listener was registered: its event is gone, so the release is made here.
            lostSession.sessionClosed(session);
        }
    }

    @Override
    public int localPort() {
        return listener.getLocalPort();
    }

    @Override
    public CleanupState release() {
        boolean first;
        synchronized (lock) {
            first = !closing;
            closing = true;
        }
        if (first) {
            CleanupState result;
            try {
                result = closeAll();
            } catch (RuntimeException e) {
                LOG.warn("A forward could not be closed completely; its close is reported as not confirmed", e);
                result = CleanupState.INCOMPLETE;
            }
            released.complete(result);
        }
        return released.join();
    }

    // ---- the connection ------------------------------------------------------------------------------------------------------------------------------------

    /** Accepts the first client, closes the listener (no second client), and starts the two pumps. */
    private void acceptOne() {
        Socket accepted;
        try {
            accepted = listener.accept();
        } catch (IOException e) {
            LOG.debug("The listener of a forward ended without a client", e);
            return;
        } finally {
            closeQuietly(listener);
        }
        synchronized (lock) {
            if (closing) {
                closeQuietly(accepted);
                return;
            }
            client = accepted;
            try {
                InputStream fromClient = accepted.getInputStream();
                OutputStream toClient = accepted.getOutputStream();
                spawn(() -> pump(fromClient, channel.getInvertedIn(), "client to target"));
                spawn(() -> pump(channel.getInvertedOut(), toClient, "target to client"));
            } catch (IOException | RuntimeException e) {
                LOG.warn("The connection of a forward could not be started; it is closed", e);
                endConnection();
            }
        }
    }

    /** Copies bytes until one end ends; then both ends are closed, so the other pump ends too. */
    private void pump(InputStream from, OutputStream to, String direction) {
        byte[] buffer = new byte[BUFFER_BYTES];
        try {
            for (int read = from.read(buffer); read >= 0; read = from.read(buffer)) {
                to.write(buffer, 0, read);
                to.flush();
            }
        } catch (IOException e) {
            LOG.debug("The pump {} of a forward ended with an I/O failure; the connection is closed", direction, e);
        } finally {
            endConnection();
        }
    }

    /** Closes the client socket, the read side of the channel and the channel itself (with the close of the protocol); does not wait. */
    private void endConnection() {
        Socket socket;
        synchronized (lock) {
            socket = client;
        }
        closeQuietly(socket);
        closeQuietly(channel.getInvertedOut());
        channel.close(false);
    }

    private void spawn(Runnable work) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        tasks.add(done);
        try {
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    LOG.warn("A task of a forward failed", e);
                } finally {
                    done.complete(null);
                }
            });
        } catch (RuntimeException e) {
            done.complete(null);
            throw e;
        }
    }

    // ---- the close -----------------------------------------------------------------------------------------------------------------------------------------

    private CleanupState closeAll() {
        session.removeSessionListener(lostSession);
        closeQuietly(listener);
        Socket socket;
        synchronized (lock) {
            socket = client;
        }
        closeQuietly(socket);
        boolean channelClosed = closeChannel();
        boolean tasksEnded = awaitTasks();
        lease.close();
        return channelClosed && tasksEnded ? CleanupState.COMPLETE : CleanupState.INCOMPLETE;
    }

    /** Closes the channel with the close of the protocol and waits for it; a close that is not answered in time is forced, and is not a confirmed close. */
    private boolean closeChannel() {
        closeQuietly(channel.getInvertedOut());
        CloseFuture channelClose = channel.close(false);
        boolean confirmed = false;
        try {
            confirmed = channelClose.await(grace);
        } catch (InterruptedIOException e) {
            Thread.currentThread().interrupt();
            LOG.warn("The wait for the close of a forward's channel was interrupted", e);
        } catch (IOException e) {
            LOG.warn("The wait for the close of a forward's channel failed", e);
        }
        if (!confirmed) {
            channel.close(true);
        }
        return confirmed;
    }

    private boolean awaitTasks() {
        try {
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture<?>[0])).get(grace.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            LOG.warn("A task of a forward did not end within the stop grace", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("The wait for the tasks of a forward was interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("A task of a forward completes normally only", e);
        }
        return false;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOG.debug("A resource of a forward did not close cleanly; the close goes on", e);
        }
    }
}
