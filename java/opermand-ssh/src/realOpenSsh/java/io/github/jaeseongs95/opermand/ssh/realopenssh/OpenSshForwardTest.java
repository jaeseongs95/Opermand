package io.github.jaeseongs95.opermand.ssh.realopenssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.CommandKind;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ForwardHandle;
import io.github.jaeseongs95.opermand.app.remote.ForwardOpenResult;
import io.github.jaeseongs95.opermand.app.remote.ForwardRequest;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.StdinBytes;
import io.github.jaeseongs95.opermand.app.remote.StreamEnd;
import io.github.jaeseongs95.opermand.app.remote.StreamHandle;
import io.github.jaeseongs95.opermand.app.remote.StreamOpenResult;
import io.github.jaeseongs95.opermand.app.remote.StreamRequest;
import io.github.jaeseongs95.opermand.app.remote.TransferFailureCause;
import io.github.jaeseongs95.opermand.domain.operation.CleanupState;
import io.github.jaeseongs95.opermand.domain.operation.ExecutionReason;
import io.github.jaeseongs95.opermand.domain.operation.ExitStatus;
import io.github.jaeseongs95.opermand.domain.operation.RemoteState;
import io.github.jaeseongs95.opermand.ssh.testkit.EchoTarget;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

/**
 * The local forward and the streamed command of the product (stage 3 design 2.2; cases RC-A-715, RC-A-716, RC-A-719, RC-A-816, RC-A-817, RC-A-829; defects D-0021, D-0022,
 * D-0091) against a real OpenSSH server, on the Linux and the Windows runner alike. The server connects to a target on its own loopback: a {@code direct-tcpip} channel of
 * the real {@code sshd} to an echo service of the test JVM, and a real command (not a scripted one) whose standard streams the test reads and writes.
 *
 * <p>The races of the open (a cancel or a time limit while the server holds the answer) are cases of the embedded server (ForwardTest, StreamTest): a real server cannot be made
 * to hold an answer for as long as the test needs, and a target that never answers is not the same on every network. What only a real server shows is here: that the
 * channel really carries bytes, that the server really closes its side when the owner releases, and what a real {@code sshd} does with the signals of a stop.
 *
 * <p>They run only through the Gradle task {@code realOpenSshTest}.
 */
@Timeout(300)
class OpenSshForwardTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    private static final byte[] LOOPBACK = {127, 0, 0, 1};
    private static final byte[] BYTES = {0, (byte) 255, 13, 10, (byte) 128, 'x'};

    @TempDir
    Path root;

    private final OpenSshTarget target = OpenSshTarget.fromSystemProperties();

    private static ExecutionLimits limits(long graceMillis) {
        return new ExecutionLimits(Duration.ofSeconds(60), Duration.ofMillis(graceMillis), DataSize.ofMegabytes(1));
    }

    private static ExecutionControl control() {
        return new ExecutionControl(new CancelSource(), () -> { });
    }

    private static ForwardHandle opened(ForwardOpenResult result) {
        assertThat(result).as("the forward opened: %s", result).isInstanceOf(ForwardOpenResult.Opened.class);
        return ((ForwardOpenResult.Opened) result).handle();
    }

    private static StreamHandle opened(StreamOpenResult result) {
        assertThat(result).as("the command started: %s", result).isInstanceOf(StreamOpenResult.Opened.class);
        return ((StreamOpenResult.Opened) result).handle();
    }

    private static Socket connectTo(int port) throws IOException {
        Socket socket = new Socket(InetAddress.getByAddress(LOOPBACK), port);
        socket.setSoTimeout((int) WAIT.toMillis());
        return socket;
    }

    /** The command of the target's shell that sends its standard input back and ends when the input ends. */
    private String echoCommand() {
        return switch (target.platform()) {
            case LINUX -> "cat";
            case WINDOWS -> {
                String python = System.getProperty("portal.test.openssh.windows-python");
                if (python == null || python.isBlank()) {
                    throw new IllegalStateException("the system property portal.test.openssh.windows-python is not set: the realOpenSshTest task passes it for a Windows server");
                }
                String line = python + " -u -c \"import sys;[(sys.stdout.buffer.write(d),sys.stdout.buffer.flush()) for d in iter(lambda:sys.stdin.buffer.read1(65536),b'')]\"";
                // cmd.exe /c strips the first and the last quote of a line that starts with one: the extra pair keeps a path with spaces whole.
                yield python.contains(" ") ? "\"" + line + "\"" : line;
            }
        };
    }

    /** A command that runs for twenty seconds and ignores the end of its input. */
    private String longCommand() {
        return switch (target.platform()) {
            case LINUX -> "sleep 20";
            case WINDOWS -> "ping -n 20 127.0.0.1";
        };
    }

    /** The default shell of a Windows server answers the exec request of OpenSSH for Windows only as far as the pilots showed; a POSIX server always does. */
    private ExecReply reply() {
        return target.platform() == OpenSshTarget.Platform.LINUX ? ExecReply.AWAITED : ExecReply.NOT_AWAITED;
    }

    @Test
    @DisplayName("RC-A-715 RC-A-719 D-0021 a real sshd carries a forward to a target on its own loopback, and the release confirms the channel, the listener and the target connection closed")
    void aForwardCarriesBytesAndIsReleased() throws Exception {
        try (EchoTarget echo = EchoTarget.start(); RealAdapter real = RealAdapter.start(target, root)) {
            ForwardHandle handle = opened(real.forwarder().open(new ForwardRequest(real.remote(), "127.0.0.1", echo.port(), limits(10_000)), control()));
            int port = handle.localPort();

            try (Socket client = connectTo(port)) {
                client.getOutputStream().write(BYTES);
                client.getOutputStream().flush();
                assertThat(client.getInputStream().readNBytes(BYTES.length)).as("the echo came back through the real channel").isEqualTo(BYTES);
            }

            assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
            echo.endedConnections().await(1, WAIT);
            assertThatThrownBy(() -> connectTo(port)).as("no listener").isInstanceOf(ConnectException.class);
            assertThat(handle.release()).isEqualTo(CleanupState.COMPLETE);
        }
    }

    @Test
    @DisplayName("RC-A-716 D-0022 a real sshd closes the target connection of a forward that nobody used, and a target that refuses is Refused(CHANNEL_OPEN_FAILED) with the session kept")
    void aForwardNobodyUsedAndATargetThatRefuses() throws Exception {
        try (EchoTarget echo = EchoTarget.start(); RealAdapter real = RealAdapter.start(target, root)) {
            ForwardHandle idle = opened(real.forwarder().open(new ForwardRequest(real.remote(), "127.0.0.1", echo.port(), limits(10_000)), control()));
            echo.acceptedConnections().await(1, WAIT);

            assertThat(idle.release()).isEqualTo(CleanupState.COMPLETE);
            echo.endedConnections().await(1, WAIT);

            ForwardOpenResult refused = real.forwarder().open(new ForwardRequest(real.remote(), "127.0.0.1", EchoTarget.refusedPort(), limits(10_000)), control());
            if (target.platform() == OpenSshTarget.Platform.LINUX) {
                assertThat(refused).as("sshd of Linux answers a refused target at the open").isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));
            } else if (refused instanceof ForwardOpenResult.Opened late) {
                // Observed on the Windows runner (runs 44904063661 and 44904514533): OpenSSH for Windows confirms the channel although its connection to the target failed, and
                // then neither sends data nor closes the channel (a read of 60 s saw nothing). So the refusal is not visible at the open on this server, and the owner cannot
                // tell the forward from a working one that is silent. What the adapter owes is that the release still closes it and says so.
                assertThat(late.handle().release()).as("the channel that the Windows server left open is closed and confirmed").isEqualTo(CleanupState.COMPLETE);
            } else {
                assertThat(refused).isEqualTo(new ForwardOpenResult.Refused(TransferFailureCause.CHANNEL_OPEN_FAILED));
            }
            ForwardHandle next = opened(real.forwarder().open(new ForwardRequest(real.remote(), "127.0.0.1", echo.port(), limits(10_000)), control()));
            assertThat(next.release()).as("the session that carried the refused forward still carries the next").isEqualTo(CleanupState.COMPLETE);
        }
    }

    @Test
    @DisplayName("RC-A-816 a real command without a terminal gets its input byte for byte and sends its output back, and a command that ended with its input is released as EXIT, EXITED, COMPLETE")
    void aStreamedCommandRoundTrip() throws Exception {
        try (RealAdapter real = RealAdapter.start(target, root)) {
            PreparedCommand command = new PreparedCommand(echoCommand(), StdinBytes.none(), CommandKind.COMMAND);
            StreamHandle handle = opened(real.streams().open(new StreamRequest(real.remote(), command, limits(10_000), reply()), control()));

            handle.input().write(BYTES);
            handle.input().flush();
            assertThat(handle.output().readNBytes(BYTES.length)).as("what the real command wrote back").isEqualTo(BYTES);
            handle.input().close();
            assertThat(handle.output().readAllBytes()).isEmpty();

            StreamEnd end = handle.release();
            assertThat(end).isEqualTo(new StreamEnd(ExecutionReason.EXIT, RemoteState.EXITED, new ExitStatus.Exited(0), CleanupState.COMPLETE));
        }
    }

    @Test
    @DisplayName("RC-A-817 D-0091 the release of a running command claims the end of it only with an exit status or signal: a real sshd of Linux ends it, and on either system the cleanup is complete only with that evidence")
    void aReleaseClaimsOnlyWhatItSaw() throws Exception {
        try (RealAdapter real = RealAdapter.start(target, root)) {
            PreparedCommand command = new PreparedCommand(longCommand(), StdinBytes.none(), CommandKind.COMMAND);
            StreamHandle handle = opened(real.streams().open(new StreamRequest(real.remote(), command, limits(3_000), reply()), control()));

            StreamEnd end = handle.release();

            assertThat(end.reason()).isEqualTo(ExecutionReason.CANCEL);
            if (target.platform() == OpenSshTarget.Platform.LINUX) {
                assertThat(end.remoteState()).as("a real sshd of Linux delivers INT and reports the end: %s", end).isEqualTo(RemoteState.EXITED);
                assertThat(end.cleanup()).isEqualTo(CleanupState.COMPLETE);
            } else {
                // Win32-OpenSSH may leave the command running after the channel closed (D-0091): then nothing proves its end, and the result must not say it ended.
                assertThat(end.cleanup() == CleanupState.COMPLETE).as("complete only with an exit: %s", end).isEqualTo(end.remoteState() == RemoteState.EXITED);
            }
            assertThat(handle.release()).as("a second release gives the result of the first").isSameAs(end);
        }
    }
}
