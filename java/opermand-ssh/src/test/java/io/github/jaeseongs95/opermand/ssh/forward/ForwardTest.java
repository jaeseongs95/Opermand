package io.github.jaeseongs95.opermand.ssh.forward;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ForwardHandle;
import io.github.jaeseongs95.opermand.app.remote.ForwardOpenResult;
import io.github.jaeseongs95.opermand.app.remote.SshSettings;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.app.remote.UnconfirmedCause;
import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.ssh.session.AcquireResult;
import io.github.jaeseongs95.opermand.ssh.session.ChannelLease;
import io.github.jaeseongs95.opermand.ssh.testkit.AdapterFixture;
import io.github.jaeseongs95.opermand.ssh.testkit.EchoTarget;
import io.github.jaeseongs95.opermand.ssh.testkit.EmbeddedSshServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.util.unit.DataSize;

/**
 * The local forward of the SSH adapter (stage 3 design 2.2, defects D-0021 and D-0022) with the cases of 2.x it replaces (RC-A-715 to RC-A-725): the real
 * {@link MinaRemoteForwarder}, session pool and MINA client against the MINA test server, whose {@code direct-tcpip} channels connect to a real {@link EchoTarget}.
 *
 * <p>The races are made by the server, not by the start order of threads: {@link EmbeddedSshServer#holdChannelOpens()} leaves a request to open a channel unanswered until the
 * test lets go, and the test acts only after the server has recorded the request ({@code channelOpenRequests}), so the open is known to be pending when the test cancels it,
 * lets its time pass or cuts the session. Every wait is an event wait.
 */
@Timeout(120)
class ForwardTest {

    private static final String USER = "tester";
    private static final String PASSWORD = "secret";
    private static final String ALIAS = "main";
    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final String SERVER_FORWARD = "TcpipServerChannel";

    private EmbeddedSshServer server;
    private EchoTarget target;
    private AdapterFixture adapter;

    @AfterEach
    void stop() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.close();
        }
        if (target != null) {
            target.close();
        }
    }

    private void startAll(int channelPermits) throws IOException {
        server = EmbeddedSshServer.builder().password(USER, PASSWORD).allowTcpForwarding().start();
        target = EchoTarget.start();
        SshSettings defaults = AdapterFixture.defaultSettings();
        SshSettings settings = new SshSettings(defaults.connectTimeout(), defaults.heartbeatInterval(), defaults.heartbeatNoReplyMax(), defaults.idleProbeAfter(),
            defaults.execReplyTimeout(), channelPermits, defaults.consecutiveOpenRejections(), defaults.hostKeyMode(), defaults.knownHosts());
        adapter = AdapterFixture.start(settings);
        adapter.profiles().put(ALIAS, AdapterFixture.profileFor(server, USER));
        adapter.credentials().password(ALIAS, PASSWORD);
    }

    private static ExecutionLimits limits(long deadlineMillis, long graceMillis) {
        return new ExecutionLimits(Duration.ofMillis(deadlineMillis), Duration.ofMillis(graceMillis), DataSize.ofMegabytes(1));
    }

    private ForwardOpenResult open(int port, ExecutionLimits limits, ExecutionControl control) {
        return adapter.forwarder().open(AdapterFixture.forwardTo(ALIAS, "127.0.0.1", port, limits), control);
    }

    private ForwardOpenResult open(int port) {
        return open(port, limits(30_000, 5_000), AdapterFixture.control());
    }

    private CompletableFuture<ForwardOpenResult> openAsync(int port, ExecutionLimits limits, ExecutionControl control) {
        return CompletableFuture.supplyAsync(() -> open(port, limits, control), adapter.async());
    }

    private static ForwardHandle opened(ForwardOpenResult result) {
        assertThat(result).isInstanceOf(ForwardOpenResult.Opened.class);
        return ((ForwardOpenResult.Opened) result).handle();
    }

    /** The session of the pool (made by the first request), without keeping a permit. */
    private ClientSession poolSession() {
        AcquireResult result = adapter.pool().acquire(new ProfileAlias(ALIAS), new CancelSource());
        assertThat(result).isInstanceOf(AcquireResult.Leased.class);
        try (ChannelLease lease = ((AcquireResult.Leased) result).lease()) {
            return lease.session();
        }
    }

    /** Completes with the next channel that the client creates on the session (before the adapter has attached anything to it). */
    private static CompletableFuture<Channel> nextChannelOf(ClientSession session) {
        CompletableFuture<Channel> created = new CompletableFuture<>();
        session.addChannelListener(new ChannelListener() {
            @Override
            public void channelInitialized(Channel channel) {
                created.complete(channel);
            }
        });
        return created;
    }

    /**
     * Completes when the channel has been closed on the client and the adapter has given its permit back: the listener is put on the close future after the adapter's own
     * ({@code PermitReturn}), which is attached before the request to open the channel is written, so it has run when this one does. Call it after the server recorded the
     * request.
     */
    private static CompletableFuture<Void> closedAndPermitBack(CompletableFuture<Channel> created) throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        created.get(WAIT.toSeconds(), TimeUnit.SECONDS).addCloseFutureListener(future -> closed.complete(null));
        return closed;
    }

    private static Socket connectTo(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getByAddress(LOOPBACK), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        return socket;
    }

    /** The threads of the adapter's task executor that are alive now (the fixture names them {@code adapter-test-<n>}; its scheduler has another prefix). */
    private static List<Thread> executorThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().matches("adapter-test-\\d+")).toList();
    }

    /** Waits (an event wait: the end of the thread) for the threads and says whether every one of them ended. */
    private static boolean allEnded(List<Thread> threads) throws InterruptedException {
        boolean ended = true;
        for (Thread thread : threads) {
            ended &= thread.join(WAIT);
        }
        return ended;
    }

    @Test
    @DisplayName("RC-A-715 RC-A-719 a forward carries bytes both ways to the fixed target, only the first client gets it, and its release confirms the listener, the channel and the threads closed")
    void bytesBothWaysAndAConfirmedClose() throws Exception {
        startAll(1);
        ForwardHandle handle = opened(open(target.port()));
        int port = handle.localPort();
        List<Thread> serving = executorThreads();

        byte[] bytes = {0, (byte) 255, 13, 10, (byte) 128};
        List<Thread> bridged;
        try (Socket client = connectTo(port)) {
            client.getOutputStream().write(bytes);
            client.getOutputStream().flush();
            assertThat(client.getInputStream().readNBytes(bytes.length)).as("the echo of the target came back through the channel").isEqualTo(bytes);
            bridged = executorThreads();
            assertThatThrownBy(() -> connectTo(port)).as("the listener took the first client and closed itself: no second client gets the forward")
                .isInstanceOf(ConnectException.class);
        }

        assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
        assertThat(handle.release()).as("a second release gives the result of the first").isEqualTo(CleanupState.COMPLETE);
        assertThat(server.forwardRequests().snapshot()).as("the target of the channel is the one of the request").containsExactly(new SshdSocketAddress("127.0.0.1", target.port()));
        server.closedChannels().await(1, WAIT);
        assertThat(server.closedChannels().snapshot()).contains(SERVER_FORWARD);
        target.endedConnections().await(1, WAIT);
        assertThat(server.openForwardChannelCount()).as("no channel is left open on the server").isZero();
        assertThat(bridged).as("the two pumps of the connection were running").hasSizeGreaterThanOrEqualTo(2);
        assertThat(allEnded(serving)).as("the accept task is gone").isTrue();
        assertThat(allEnded(bridged)).as("and the two pumps").isTrue();
        assertThatThrownBy(() -> connectTo(port)).as("no listener").isInstanceOf(ConnectException.class);
        assertThat(opened(open(target.port())).release()).as("the only permit went back with the release: the next forward opens at once").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-716 a forward that nobody connected to is released too: listener closed, target connection ended, threads gone")
    void releaseWithoutAClient() throws Exception {
        startAll(8);
        ForwardHandle handle = opened(open(target.port()));
        int port = handle.localPort();
        List<Thread> serving = executorThreads();
        target.acceptedConnections().await(1, WAIT); // the channel was opened before the answer: the target has its connection already

        assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);

        target.endedConnections().await(1, WAIT);
        assertThat(allEnded(serving)).isTrue();
        assertThatThrownBy(() -> connectTo(port)).isInstanceOf(ConnectException.class);
        assertThat(server.openForwardChannelCount()).isZero();
    }

    @Test
    @DisplayName("D-0022 a target that refuses the connection is Refused(CHANNEL_OPEN_FAILED) at the open, with no listener and no thread, the session is kept and the slot is back")
    void aTargetThatRefuses() throws Exception {
        startAll(1);
        ForwardOpenResult refused = open(EchoTarget.refusedPort());

        assertThat(refused).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(allEnded(executorThreads())).isTrue();
        assertThat(server.activeSessionCount()).as("a refusing target does not cost the session").isEqualTo(1);
        ForwardHandle next = opened(open(target.port()));
        assertThat(server.acceptedConnections().size()).as("the next forward uses the same connection: nothing was reconnected").isEqualTo(1);
        assertThat(next.release()).isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("D-0022 the server not allowing forwards is a refused channel too, and no unknown profile or session is a connection failure")
    void forbiddenAndUnreachable() throws Exception {
        server = EmbeddedSshServer.builder().password(USER, PASSWORD).start(); // the default refuses direct-tcpip
        target = EchoTarget.start();
        adapter = AdapterFixture.start(AdapterFixture.defaultSettings());
        adapter.profiles().put(ALIAS, AdapterFixture.profileFor(server, USER));
        adapter.credentials().password(ALIAS, PASSWORD);

        assertThat(open(target.port())).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(adapter.forwarder().open(AdapterFixture.forwardTo("unknown", "127.0.0.1", target.port(), limits(30_000, 5_000)), AdapterFixture.control()))
            .isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CONNECT_FAILED));
    }

    @Test
    @DisplayName("RC-A-718 the time limit of the open: Refused(DEADLINE), not a lost transport; the session is kept, and the channel the server opens later is closed, then the slot is back")
    void theOpenTimesOut() throws Exception {
        startAll(1);
        ClientSession session = poolSession();
        CompletableFuture<Channel> created = nextChannelOf(session);
        CompletableFuture<Void> gate = server.holdChannelOpens();

        ForwardOpenResult result = open(target.port(), limits(300, 10_000), AdapterFixture.control());

        assertThat(result).as("nothing but the time limit can end an open that the server holds").isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.DEADLINE));
        CompletableFuture<Void> lateChannelClosed = closedAndPermitBack(created);
        assertThat(server.channelOpenRequests().size()).isEqualTo(1);
        assertThat(server.activeSessionCount()).as("a time limit is no reason to drop a live session").isEqualTo(1);
        assertThat(open(target.port())).as("the abandoned channel still holds the only permit: it may yet come alive")
            .isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));

        gate.complete(null);

        lateChannelClosed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        target.endedConnections().await(1, WAIT);
        server.closedChannels().await(1, WAIT);
        assertThat(server.openForwardChannelCount()).as("the channel that opened late was closed, not left open on the server").isZero();
        assertThat(server.acceptedConnections().size()).as("the same session served everything").isEqualTo(1);
        assertThat(opened(open(target.port())).release()).as("the permit of the abandoned channel went back with its close").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-718 RC-A-723 the owner's cancel of a pending open: Refused(CANCELLED), not a lost transport; the session is kept, the late channel is closed and nothing stays open")
    void theOpenIsCancelled() throws Exception {
        startAll(1);
        ClientSession session = poolSession();
        CompletableFuture<Channel> created = nextChannelOf(session);
        CompletableFuture<Void> gate = server.holdChannelOpens();
        CancelSource cancel = new CancelSource();
        CompletableFuture<ForwardOpenResult> pending = openAsync(target.port(), limits(60_000, 10_000), AdapterFixture.control(cancel));
        server.channelOpenRequests().await(1, WAIT); // the server has the request and holds it: the open is pending
        CompletableFuture<Void> lateChannelClosed = closedAndPermitBack(created);

        cancel.cancel();

        assertThat(pending.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CANCELLED));
        assertThat(server.activeSessionCount()).isEqualTo(1);

        gate.complete(null);

        lateChannelClosed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        target.endedConnections().await(1, WAIT);
        assertThat(server.openForwardChannelCount()).isZero();
        assertThat(server.acceptedConnections().size()).isEqualTo(1);
        assertThat(allEnded(executorThreads())).isTrue();
        assertThat(opened(open(target.port())).release()).isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-720 a cancel before the open sends nothing and connects to nothing")
    void cancelledBeforeTheOpen() throws Exception {
        startAll(8);
        CancelSource cancel = new CancelSource();
        cancel.cancel();

        assertThat(open(target.port(), limits(30_000, 5_000), AdapterFixture.control(cancel))).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CANCELLED));
        assertThat(server.acceptedConnections().size()).as("not even a connection to the server was made").isZero();
    }

    @Test
    @DisplayName("D-0022 a session lost while the open is pending is Unconfirmed(TransportLost): the one case that is a lost transport, and the next forward makes a new session")
    void theSessionIsLostDuringTheOpen() throws Exception {
        startAll(1);
        ClientSession session = poolSession();
        CompletableFuture<Void> gate = server.holdChannelOpens();
        CompletableFuture<ForwardOpenResult> pending = openAsync(target.port(), limits(60_000, 10_000), AdapterFixture.control());
        server.channelOpenRequests().await(1, WAIT);

        session.close(true).await(WAIT);
        gate.complete(null);

        assertThat(pending.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new ForwardOpenResult.Unconfirmed(new UnconfirmedCause.TransportLost()));
        ForwardHandle next = opened(open(target.port()));
        assertThat(server.acceptedConnections().size()).as("the dead session was replaced once").isEqualTo(2);
        assertThat(next.release()).isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("D-0021 a session that goes away first: the listener and the threads of the forward end by themselves, and the release that follows says COMPLETE")
    void theSessionGoesAwayFirst() throws Exception {
        startAll(1);
        ForwardHandle handle = opened(open(target.port()));
        int port = handle.localPort();
        List<Thread> serving = executorThreads();
        assertThat(serving).as("the accept task of the forward is running").isNotEmpty();

        server.dropConnections();

        assertThat(allEnded(serving)).as("the forward released itself: its accept task ended without the owner").isTrue();
        assertThatThrownBy(() -> connectTo(port)).as("no listener outlives the session").isInstanceOf(ConnectException.class);
        assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
        assertThat(opened(open(target.port())).release()).as("the permit went back: a new session carries the next forward").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-717 a close that the server does not answer in time is INCOMPLETE, never COMPLETE: the owner learns that the channel is not confirmed closed")
    void aCloseThatIsNotConfirmed() throws Exception {
        startAll(8);
        ForwardHandle first = opened(open(target.port(), limits(30_000, 400), AdapterFixture.control()));
        CompletableFuture<Void> gate = server.holdChannelOpens();
        CancelSource cancelSecond = new CancelSource();
        CompletableFuture<ForwardOpenResult> second = openAsync(target.port(), limits(60_000, 10_000), AdapterFixture.control(cancelSecond));
        server.channelOpenRequests().await(2, WAIT); // the second open is held on the server thread of the session: it reads nothing more, so it cannot answer the close

        CleanupState state = first.release();

        assertThat(state).isEqualTo(CleanupState.INCOMPLETE);
        cancelSecond.cancel();
        assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CANCELLED));
        gate.complete(null);
        server.closedChannels().await(2, WAIT);
        assertThat(server.openForwardChannelCount()).as("the server did close both channels once it read the closes").isZero();
    }

    @Test
    @DisplayName("D-0068 a guard that refuses and a failing open give the slot back at once: the next forward opens with the only permit, and nothing was sent for the refused one")
    void theSlotIsReturnedOnEveryPath() throws Exception {
        startAll(1);
        ExecutionControl refusing = new ExecutionControl(new CancelSource(), () -> {
            throw new IllegalStateException("the target changed");
        });

        assertThatThrownBy(() -> open(target.port(), limits(30_000, 5_000), refusing)).isInstanceOf(IllegalStateException.class).hasMessage("the target changed");

        assertThat(server.channelOpenRequests().size()).as("the guard is asked before anything is sent").isZero();
        ForwardHandle handle = opened(open(target.port()));
        assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
        assertThat(open(EchoTarget.refusedPort())).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));
        assertThat(opened(open(target.port())).release()).as("a refused open gave its permit back too").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("LocalForward preserves TCP half-close: client shutdownOutput forwards EOF to the target while keeping the return direction open for the target's response")
    void halfClosePreservesReturnDirection() throws Exception {
        startAll(1);
        try (ServerSocket halfCloseTarget = new ServerSocket(0, 1, InetAddress.getByAddress(LOOPBACK))) {
            CompletableFuture<String> serverObserved = CompletableFuture.supplyAsync(() -> {
                try (Socket accepted = halfCloseTarget.accept()) {
                    accepted.setSoTimeout((int) WAIT.toMillis());
                    String received = new String(accepted.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    accepted.getOutputStream().write("response-after-half-close".getBytes(StandardCharsets.UTF_8));
                    accepted.getOutputStream().flush();
                    return received;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, adapter.async());

            ForwardHandle handle = opened(open(halfCloseTarget.getLocalPort()));
            String response;
            try (Socket client = connectTo(handle.localPort())) {
                client.getOutputStream().write("request-before-eof".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();
                client.shutdownOutput();
                response = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }

            assertThat(serverObserved.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo("request-before-eof");
            assertThat(response).isEqualTo("response-after-half-close");
            assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
            server.closedChannels().await(1, WAIT);
            assertThat(server.openForwardChannelCount()).isZero();
        }
    }
}
