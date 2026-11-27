package io.github.jaeseongs95.portal.ssh.testkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * A TCP service on a free loopback port that sends back every byte it receives: the target a forward of the tests connects to (the test server connects to it for a
 * {@code direct-tcpip} channel). Every accepted connection and every connection that ended is recorded ({@link EventLog}, waited on without polling). Closing it ends the
 * listener and every connection that is still open.
 */
public final class EchoTarget implements AutoCloseable {

    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    private final ServerSocket listener;
    private final SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("echo-target-");
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final EventLog<Integer> accepted = new EventLog<>();
    private final EventLog<Integer> ended = new EventLog<>();

    private EchoTarget(ServerSocket listener) {
        this.listener = listener;
        this.executor.setDaemon(true);
    }

    /** Starts the target on a port the system chose. */
    public static EchoTarget start() throws IOException {
        EchoTarget target = new EchoTarget(new ServerSocket(0, 8, InetAddress.getByAddress(LOOPBACK)));
        target.executor.execute(target::acceptLoop);
        return target;
    }

    /** A loopback port on which nobody listens: a connection to it is refused. It was free a moment ago, which is as much as a test can know. */
    public static int refusedPort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK))) {
            return probe.getLocalPort();
        }
    }

    public int port() {
        return listener.getLocalPort();
    }

    /** One entry (the remote port) for every connection the target accepted. */
    public EventLog<Integer> acceptedConnections() {
        return accepted;
    }

    /** One entry (the remote port) for every connection that ended: the peer closed it, or it failed. */
    public EventLog<Integer> endedConnections() {
        return ended;
    }

    private void acceptLoop() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                open.add(socket);
                accepted.add(socket.getPort());
                executor.execute(() -> echo(socket));
            } catch (IOException e) {
                return; // closed
            }
        }
    }

    private void echo(Socket socket) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            // The peer went away or the target was closed: the connection ends either way, and the end is recorded below.
        } finally {
            open.remove(socket);
            ended.add(socket.getPort());
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : open) {
            socket.close();
        }
    }
}
