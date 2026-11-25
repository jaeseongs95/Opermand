package io.github.jaeseongs95.portal.ssh.exec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.CommandKind;
import io.github.jaeseongs95.portal.app.remote.ExecReply;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.ExecutionLimits;
import io.github.jaeseongs95.portal.app.remote.PreparedCommand;
import io.github.jaeseongs95.portal.app.remote.SshSettings;
import io.github.jaeseongs95.portal.app.remote.StdinBytes;
import io.github.jaeseongs95.portal.app.remote.StreamEnd;
import io.github.jaeseongs95.portal.app.remote.StreamHandle;
import io.github.jaeseongs95.portal.app.remote.StreamOpenResult;
import io.github.jaeseongs95.portal.app.remote.StreamRequest;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.domain.operation.CleanupState;
import io.github.jaeseongs95.portal.domain.operation.ExecutionReason;
import io.github.jaeseongs95.portal.domain.operation.ExitStatus;
import io.github.jaeseongs95.portal.domain.operation.RemoteState;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.ssh.session.AcquireResult;
import io.github.jaeseongs95.portal.ssh.session.ChannelLease;
import io.github.jaeseongs95.portal.ssh.testkit.AdapterFixture;
import io.github.jaeseongs95.portal.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.portal.ssh.testkit.ReceivedSignal;
import io.github.jaeseongs95.portal.ssh.testkit.ScriptedCommand;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.server.Signal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.util.unit.DataSize;

/**
 * The streamed command of the SSH adapter (stage 3 design 2.2, cases RC-A-816 to RC-A-829, defects D-0021, D-0022, D-0068 and D-0091): the real
 * {@link MinaRemoteStreamExecutor}, session pool and MINA client against the MINA test server and its scripted commands. The open of a channel is held by the server
 * ({@link EmbeddedSshServer#holdChannelOpens()}) where a race is needed, and the test acts only after the server recorded the request, so every wait is an event wait.
 */
@Timeout(120)
class StreamTest {

    private static final String USER = "tester";
    private static final String PASSWORD = "secret";
    private static final String ALIAS = "main";
    private static final Duration WAIT = Duration.ofSeconds(30);

    private EmbeddedSshServer server;
    private AdapterFixture adapter;

    @AfterEach
    void stop() throws IOException {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private void startAll(int channelPermits, EmbeddedSshServer.Builder builder) throws IOException {
        server = builder.password(USER, PASSWORD).start();
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

    private StreamOpenResult open(String command, ExecutionLimits limits, ExecReply reply, ExecutionControl control) {
        return adapter.streams().open(AdapterFixture.streamOf(ALIAS, command, limits, reply), control);
    }

    private StreamOpenResult open(String command) {
        return open(command, limits(30_000, 5_000), ExecReply.AWAITED, AdapterFixture.control());
    }

    private static StreamHandle opened(StreamOpenResult result) {
        assertThat(result).isInstanceOf(StreamOpenResult.Opened.class);
        return ((StreamOpenResult.Opened) result).handle();
    }

    private ClientSession poolSession() {
        AcquireResult result = adapter.pool().acquire(new ProfileAlias(ALIAS), new CancelSource());
        assertThat(result).isInstanceOf(AcquireResult.Leased.class);
        try (ChannelLease lease = ((AcquireResult.Leased) result).lease()) {
            return lease.session();
        }
    }

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

    /** Completes when the channel is closed on the client and the adapter has given its permit back (its listener was attached before the request was written). */
    private static CompletableFuture<Void> closedAndPermitBack(CompletableFuture<Channel> created) throws Exception {
        CompletableFuture<Void> closed = new CompletableFuture<>();
        created.get(WAIT.toSeconds(), TimeUnit.SECONDS).addCloseFutureListener(future -> closed.complete(null));
        return closed;
    }

    @Test
    @DisplayName("RC-A-816 bytes written to the input come back on the output unchanged, and a command that ended by itself is released as EXIT, EXITED, COMPLETE")
    void bytesBothWays() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("echo-input", ScriptedCommand.echoesInput(0)));
        StreamHandle handle = opened(open("echo-input"));
        byte[] bytes = {0, (byte) 255, (byte) 128, 10, 13, 0};

        handle.input().write(bytes);
        handle.input().flush();
        assertThat(handle.output().readNBytes(bytes.length)).as("the echo of the command, byte for byte").isEqualTo(bytes);
        handle.input().close(); // the end of the input: the command ends
        assertThat(handle.output().readAllBytes()).as("nothing more, then the end of the output").isEmpty();

        StreamEnd end = handle.release();
        assertThat(end).isEqualTo(new StreamEnd(ExecutionReason.EXIT, RemoteState.EXITED, new ExitStatus.Exited(0), CleanupState.COMPLETE));
        assertThat(handle.release()).as("a second release gives the result of the first").isSameAs(end);
        assertThat(server.execRequestCount()).as("the request is sent once").isEqualTo(1);
        assertThat(server.receivedSignals().size()).as("a command that ended by itself is sent no signal").isZero();
    }

    @Test
    @DisplayName("RC-A-816 the bytes of the request's input are written when the command starts, and the input stays open for the owner")
    void initialInput() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("echo-input", ScriptedCommand.echoesInput(0)));
        PreparedCommand command = new PreparedCommand("echo-input", new StdinBytes("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8)), CommandKind.COMMAND);
        StreamRequest request = new StreamRequest(AdapterFixture.streamOf(ALIAS, "x", limits(30_000, 5_000), ExecReply.AWAITED).target(), command, limits(30_000, 5_000),
            ExecReply.AWAITED);

        StreamHandle handle = opened(adapter.streams().open(request, AdapterFixture.control()));

        assertThat(new String(handle.output().readNBytes(5), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("hello");
        handle.input().write('!');
        handle.input().flush();
        assertThat(handle.output().read()).as("the input was still open").isEqualTo('!');
        handle.input().close();
        assertThat(handle.output().readAllBytes()).isEmpty();
        assertThat(handle.release().remoteState()).isEqualTo(RemoteState.EXITED);
    }

    @Test
    @DisplayName("RC-A-817 RC-A-819 a release stops a running command: the end of the input, INT, then the end is seen: CANCEL, EXITED with the code, COMPLETE")
    void releaseOfACommandThatEndsOnTheSignal() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("serve", ScriptedCommand.endsOnSignal(Signal.INT, 130)));
        StreamHandle handle = opened(open("serve", limits(30_000, 5_000), ExecReply.NOT_AWAITED, AdapterFixture.control()));
        server.startedCommands().await(1, WAIT);

        StreamEnd end = handle.release();

        assertThat(end).isEqualTo(new StreamEnd(ExecutionReason.CANCEL, RemoteState.EXITED, new ExitStatus.Exited(130), CleanupState.COMPLETE));
        assertThat(server.receivedSignals().snapshot()).containsExactly(new ReceivedSignal("serve", Signal.INT));
    }

    @Test
    @DisplayName("RC-A-817 D-0091 a command that ignores every signal: the channel is closed after the grace and the release says UNKNOWN and INCOMPLETE, because a close proves nothing")
    void aCloseIsNoEvidenceOfTheEnd() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("stubborn", ScriptedCommand.hangs()));
        StreamHandle handle = opened(open("stubborn", limits(30_000, 600), ExecReply.NOT_AWAITED, AdapterFixture.control()));
        server.startedCommands().await(1, WAIT);

        StreamEnd end = handle.release();

        assertThat(end).isEqualTo(new StreamEnd(ExecutionReason.CANCEL, RemoteState.UNKNOWN, new ExitStatus.NoExit(), CleanupState.INCOMPLETE));
        server.receivedSignals().await(3, WAIT);
        assertThat(server.receivedSignals().snapshot().stream().map(ReceivedSignal::signal).toList()).containsExactly(Signal.INT, Signal.TERM, Signal.KILL);
        server.destroyedCommands().await(1, WAIT);
        assertThat(handle.release()).isSameAs(end);
    }

    @Test
    @DisplayName("RC-A-820 a cancel before the open sends nothing and does not even connect")
    void cancelledBeforeTheOpen() throws Exception {
        startAll(8, EmbeddedSshServer.builder());
        CancelSource cancel = new CancelSource();
        cancel.cancel();

        assertThat(open("anything", limits(30_000, 5_000), ExecReply.AWAITED, AdapterFixture.control(cancel))).isEqualTo(new StreamOpenResult.Refused(TransferFailureCause.CANCELLED));
        assertThat(server.acceptedConnections().size()).isZero();
        assertThat(server.execRequestCount()).isZero();
    }

    @Test
    @DisplayName("RC-A-821 a cancel while the open is held: Refused(CANCELLED), the command never starts, the session is kept and the slot is back")
    void cancelledWhileTheOpenIsPending() throws Exception {
        startAll(1, EmbeddedSshServer.builder().script("serve", ScriptedCommand.endsOnSignal(Signal.INT, 130)));
        ClientSession session = poolSession();
        CompletableFuture<Channel> created = nextChannelOf(session);
        CompletableFuture<Void> gate = server.holdChannelOpens();
        CancelSource cancel = new CancelSource();
        CompletableFuture<StreamOpenResult> pending = CompletableFuture.supplyAsync(() -> open("serve", limits(60_000, 10_000), ExecReply.AWAITED, AdapterFixture.control(cancel)),
            adapter.async());
        server.channelOpenRequests().await(1, WAIT);
        CompletableFuture<Void> channelGone = closedAndPermitBack(created);

        cancel.cancel();

        assertThat(pending.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isEqualTo(new StreamOpenResult.Refused(TransferFailureCause.CANCELLED));
        gate.complete(null);
        channelGone.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertThat(server.execRequestCount()).as("the request of the command was never written").isZero();
        assertThat(server.activeSessionCount()).as("a cancel does not drop a live session").isEqualTo(1);
        assertThat(server.acceptedConnections().size()).isEqualTo(1);
        StreamHandle next = opened(open("serve"));
        assertThat(next.release().cleanup()).as("the only permit came back with the channel: the next command runs").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-822 D-0068 a guard that refuses sends nothing and gives the slot back at once")
    void theGuardRefuses() throws Exception {
        startAll(1, EmbeddedSshServer.builder().script("serve", ScriptedCommand.endsOnSignal(Signal.INT, 130)));
        ExecutionControl refusing = new ExecutionControl(new CancelSource(), () -> {
            throw new IllegalStateException("guard denied");
        });

        assertThatThrownBy(() -> open("serve", limits(30_000, 5_000), ExecReply.AWAITED, refusing)).isInstanceOf(IllegalStateException.class).hasMessage("guard denied");

        assertThat(server.execRequestCount()).isZero();
        assertThat(server.channelOpenRequests().size()).isZero();
        assertThat(opened(open("serve")).release().remoteState()).as("the only permit is back").isEqualTo(RemoteState.EXITED);
    }

    @Test
    @DisplayName("RC-A-829 a target that refuses the command: Refused(CHANNEL_OPEN_FAILED) after one request, no replay, and the slot is back")
    void theTargetRefusesTheCommand() throws Exception {
        startAll(1, EmbeddedSshServer.builder().script("no", ScriptedCommand.refused()).script("serve", ScriptedCommand.endsOnSignal(Signal.INT, 130)));

        assertThat(open("no")).isEqualTo(new StreamOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));

        assertThat(server.execRequestCount()).as("sent once and never again").isEqualTo(1);
        assertThat(server.acceptedConnections().size()).isEqualTo(1);
        assertThat(opened(open("serve")).release().cleanup()).as("the refused command's channel gave its permit back").isEqualTo(CleanupState.COMPLETE);
    }

    @Test
    @DisplayName("RC-A-827 a session lost after the open: the release says TRANSPORT_LOST and COMPLETE (nothing is left to close), the command is not sent again, the next one uses a new session")
    void theSessionIsLost() throws Exception {
        startAll(1, EmbeddedSshServer.builder().script("serve", ScriptedCommand.hangs()));
        ClientSession session = poolSession();
        StreamHandle handle = opened(open("serve", limits(30_000, 5_000), ExecReply.NOT_AWAITED, AdapterFixture.control()));
        server.startedCommands().await(1, WAIT);

        session.close(true).await(WAIT);

        assertThat(handle.output().readAllBytes()).as("the output ends").isEmpty();
        StreamEnd end = handle.release();
        assertThat(end).isEqualTo(new StreamEnd(ExecutionReason.TRANSPORT_LOST, RemoteState.UNKNOWN, new ExitStatus.NoExit(), CleanupState.COMPLETE));
        assertThat(server.execRequestCount()).as("no automatic replay").isEqualTo(1);
        StreamHandle next = opened(open("serve", limits(30_000, 300), ExecReply.NOT_AWAITED, AdapterFixture.control()));
        assertThat(server.acceptedConnections().size()).as("the dead session was replaced once").isEqualTo(2);
        assertThat(next.release().reason()).isEqualTo(ExecutionReason.CANCEL);
    }

    @Test
    @DisplayName("RC-A-818 the standard error is read and dropped: a command that writes a lot to it neither blocks nor mixes it into the output")
    void theErrorOutputIsDrained() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("noisy", ScriptedCommand.emitsBytes(10, 300_000, 0)));
        StreamHandle handle = opened(open("noisy"));

        assertThat(handle.output().readAllBytes()).as("only the standard output, and the command ended although nobody reads the standard error")
            .isEqualTo("xxxxxxxxxx".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        assertThat(handle.release()).isEqualTo(new StreamEnd(ExecutionReason.EXIT, RemoteState.EXITED, new ExitStatus.Exited(0), CleanupState.COMPLETE));
    }

    @Test
    @DisplayName("RC-A-826 a non-zero exit is an exit like another: the release carries the code")
    void aNonZeroExit() throws Exception {
        startAll(8, EmbeddedSshServer.builder().script("fails", ScriptedCommand.exits(9, "", "boom\n")));
        StreamHandle handle = opened(open("fails"));

        assertThat(handle.output().readAllBytes()).isEmpty();

        assertThat(handle.release()).isEqualTo(new StreamEnd(ExecutionReason.EXIT, RemoteState.EXITED, new ExitStatus.Exited(9), CleanupState.COMPLETE));
    }

}
