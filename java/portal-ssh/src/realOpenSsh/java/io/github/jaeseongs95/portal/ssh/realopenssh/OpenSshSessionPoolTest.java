package io.github.jaeseongs95.portal.ssh.realopenssh;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.jaeseongs95.portal.app.output.StreamKind;
import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.CommandKind;
import io.github.jaeseongs95.portal.app.remote.CredentialLookup;
import io.github.jaeseongs95.portal.app.remote.ExecReply;
import io.github.jaeseongs95.portal.app.remote.ExecutionControl;
import io.github.jaeseongs95.portal.app.remote.ExecutionLimits;
import io.github.jaeseongs95.portal.app.remote.HostKeyMode;
import io.github.jaeseongs95.portal.app.remote.PreparedCommand;
import io.github.jaeseongs95.portal.app.remote.ProfileLookup;
import io.github.jaeseongs95.portal.app.remote.RemoteExecution;
import io.github.jaeseongs95.portal.app.remote.RemoteTarget;
import io.github.jaeseongs95.portal.app.remote.SecretValue;
import io.github.jaeseongs95.portal.app.remote.SinkResult;
import io.github.jaeseongs95.portal.app.remote.SshCredential;
import io.github.jaeseongs95.portal.app.remote.SshSettings;
import io.github.jaeseongs95.portal.app.remote.StdinBytes;
import io.github.jaeseongs95.portal.domain.id.TargetId;
import io.github.jaeseongs95.portal.domain.operation.ExecutionOutcome;
import io.github.jaeseongs95.portal.domain.operation.OperationState;
import io.github.jaeseongs95.portal.domain.target.AccessMode;
import io.github.jaeseongs95.portal.domain.target.AuthMethod;
import io.github.jaeseongs95.portal.domain.target.ConnectionProfile;
import io.github.jaeseongs95.portal.domain.target.ProfileAlias;
import io.github.jaeseongs95.portal.domain.target.Setting;
import io.github.jaeseongs95.portal.domain.target.TargetRevision;
import io.github.jaeseongs95.portal.domain.target.TtyMode;
import io.github.jaeseongs95.portal.ssh.exec.ChannelSignals;
import io.github.jaeseongs95.portal.ssh.exec.ExecOutcomes;
import io.github.jaeseongs95.portal.ssh.exec.MinaRemoteExecutor;
import io.github.jaeseongs95.portal.ssh.hostkey.HostKeyEvents;
import io.github.jaeseongs95.portal.ssh.hostkey.HostKeyRefusal;
import io.github.jaeseongs95.portal.ssh.hostkey.ServerKeyPolicy;
import io.github.jaeseongs95.portal.ssh.profile.AclFileAccessRule;
import io.github.jaeseongs95.portal.ssh.profile.FileAccessGuard;
import io.github.jaeseongs95.portal.ssh.profile.PosixFileAccessRule;
import io.github.jaeseongs95.portal.ssh.session.AcquireResult;
import io.github.jaeseongs95.portal.ssh.session.ChannelLease;
import io.github.jaeseongs95.portal.ssh.session.MinaClientFactory;
import io.github.jaeseongs95.portal.ssh.session.ProxyJumpResolver;
import io.github.jaeseongs95.portal.ssh.session.SessionConnector;
import io.github.jaeseongs95.portal.ssh.session.SessionReapSettings;
import io.github.jaeseongs95.portal.ssh.session.SshAlgorithmPolicy;
import io.github.jaeseongs95.portal.ssh.session.SshSessionPool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Case S-D0038-01 (docs/migration/regression-cases.part-b.md, section 2, "S cases"): the session pool of the product ({@link SshSessionPool} with its
 * {@link SessionConnector} and the {@link MinaRemoteExecutor}, wired as the Spring context wires them) against a real OpenSSH server whose own process of an established
 * session is ended by the server side.
 *
 * <p><b>This test fixes a category; it does not explain the defect.</b> Defect SSH_PORTAL-D-0038 was recorded against 2.x as "the next request fails after the SSH session
 * was closed on the server side". Its cause was never confirmed: the 2.x test asserted only a hypothesis, and the CI pilot of stage 3 (OpenSshSessionPilotTest) used a bare MINA
 * session and never passed through the pool. This test is the first one that runs the 3.0 pool against a real {@code sshd} that closed a session: after the close, the next request must
 * make one new connection and succeed, and nothing may be replayed. A green result shows that this scenario works on the 3.0 pool; it does not show what went wrong in 2.x.
 *
 * <p><b>How the server closes the session (Linux).</b> The test runs {@code kill -9 $PPID} on an exec channel of the pooled session. The shell that {@code sshd} starts for an exec
 * request is a child of the session process of {@code sshd} (the {@code sshd: user@notty} process, or {@code sshd-session} from OpenSSH 9.8), which after the privilege separation
 * runs as the account itself, so the account may end it. The server then closes the TCP connection without any SSH disconnect message, which is what a client sees when a
 * server, a restart or a firewall ends a session. Before it kills, the test reads {@code /proc/$PPID/comm} and requires {@code sshd} in it, so the test cannot end another process of the
 * account. Nothing the product does closes the session: the test never calls close on it.
 *
 * <p><b>Windows is not run.</b> The session process of the Windows OpenSSH server and its security context are not established for this test: whether a non-administrator
 * account can end its own session process through an exec channel was not shown, and no other way of closing a session from the server side is part of the CI preparation
 * script ({@code java/ci/openssh-windows.cmd}). A Windows run is skipped with an assumption that names this reason; a test that ends in an unverified way would not tell the
 * defect from the method. The Gradle task {@code realOpenSshTest} still discovers the test on both platforms, so it does not fail for running no test (failOnNoDiscoveredTests).
 *
 * <p>They run only through the Gradle task {@code realOpenSshTest}; nothing here was run on the machine that wrote it.
 */
@Timeout(300)
class OpenSshSessionPoolTest {

    private static final String ALIAS = "real";
    private static final String BEFORE = "portal-d0038-before-close";
    private static final String AFTER = "portal-d0038-after-close";

    @TempDir
    Path root;

    private final OpenSshTarget target = OpenSshTarget.fromSystemProperties();
    private ThreadPoolTaskScheduler scheduler;
    private SimpleAsyncTaskExecutor taskExecutor;
    private SshSessionPool pool;
    private MinaRemoteExecutor executor;

    @AfterEach
    void stop() {
        if (pool != null) {
            pool.destroy();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    /** The pool and the executor wired by hand as the Spring context wires them, over the real server: key login, host key checking on (strict, the known_hosts of the target). */
    private void startAdapter() throws IOException {
        FileAccessGuard guard = new FileAccessGuard(List.of(new PosixFileAccessRule(), new AclFileAccessRule()));
        Path record = root.resolve("private-state").resolve("known_hosts");
        guard.createPrivateFile(record);
        Files.writeString(record, Files.readString(target.knownHosts())); // into the private file the guard made, as RealHostKeyTest does
        SshSettings defaults = SshSettings.defaults(record);
        SshSettings settings = new SshSettings(defaults.connectTimeout(), defaults.heartbeatInterval(), defaults.heartbeatNoReplyMax(), defaults.idleProbeAfter(),
            defaults.execReplyTimeout(), defaults.channelPermits(), defaults.consecutiveOpenRejections(), HostKeyMode.STRICT, record);

        ConnectionProfile profile = new ConnectionProfile(target.host(), target.port(), target.user(), Setting.unset(), Setting.unset(), AuthMethod.KEY, Setting.unset(),
            Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY);
        SecretValue key = SecretValue.of(Files.readString(target.keyFile()));
        ProxyJumpResolver resolver = new ProxyJumpResolver(alias -> new ProfileLookup.Found(profile),
            (alias, found) -> new CredentialLookup.Resolved(new SshCredential.PrivateKey(key, Setting.unset(), Setting.unset())));
        ServerKeyPolicy hostKeys = new ServerKeyPolicy(settings, guard, new HostKeyEvents() {
            @Override
            public void refused(HostKeyRefusal refusal) {
                // the refusal shows in the result of the connection
            }

            @Override
            public void insecureModeActive() {
                // strict mode here
            }
        });

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("real-openssh-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        taskExecutor = new SimpleAsyncTaskExecutor("real-openssh-");
        taskExecutor.setDaemon(true);
        SessionConnector connector = new SessionConnector(resolver, new MinaClientFactory(settings, new SshAlgorithmPolicy()), hostKeys, settings);
        pool = new SshSessionPool(settings, connector, Clock.systemUTC(), taskExecutor, scheduler, SessionReapSettings.defaults());
        pool.afterPropertiesSet();
        executor = new MinaRemoteExecutor(pool, scheduler, taskExecutor, Clock.systemUTC(), new ChannelSignals(), settings, new ExecOutcomes());
    }

    /** One request through the executor of the product: it asks the pool for the session, so the pool decides whether to reuse or to reconnect. */
    private Run run(String commandLine) {
        RemoteTarget remote = new RemoteTarget(new TargetId("real-openssh"), TargetRevision.of("0".repeat(64)), new ProfileAlias(ALIAS), AccessMode.READ_WRITE);
        RemoteExecution request = new RemoteExecution(remote, new PreparedCommand(commandLine, StdinBytes.none(), CommandKind.COMMAND), ExecutionLimits.defaults(),
            ExecReply.AWAITED);
        StringBuffer stdout = new StringBuffer();
        ExecutionOutcome outcome = executor.run(request, frame -> {
            if (frame.kind() == StreamKind.STDOUT) {
                stdout.append(new String(frame.data(), StandardCharsets.UTF_8));
            }
            return new SinkResult.Accepted();
        }, new ExecutionControl(new CancelSource(), () -> { })).outcome();
        return new Run(outcome, stdout.toString());
    }

    private record Run(ExecutionOutcome outcome, String stdout) {
    }

    private ChannelLease lease() {
        AcquireResult result = pool.acquire(new ProfileAlias(ALIAS), new CancelSource());
        assertThat(result).as("the pool gave a session").isInstanceOf(AcquireResult.Leased.class);
        return ((AcquireResult.Leased) result).lease();
    }

    /** Runs a command on an exec channel of the session and returns its standard output once the channel is closed. */
    private static String exec(ClientSession session, String commandLine) throws IOException {
        try (ChannelExec channel = session.createExecChannel(commandLine)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            channel.setOut(out);
            channel.setErr(OutputStream.nullOutputStream());
            channel.open().verify(OpenSshClient.LIMIT);
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), OpenSshClient.LIMIT.toMillis());
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("S-D0038-01 (cause of D-0038 not confirmed; this fixes the category) a real sshd ends the session of the pool: the next request through the pool makes one new connection and runs once")
    void aRequestAfterTheServerClosedTheSession() throws Exception {
        assumeTrue(target.platform() == OpenSshTarget.Platform.LINUX,
            "the server side close is run on a Linux sshd only: how a non-administrator account ends its own session process of the Windows OpenSSH server is not established, "
                + "and java/ci/openssh-windows.cmd has no other way to close a session from the server side");
        startAdapter();

        Run before = run("echo " + BEFORE);
        assertThat(before.outcome().verdict()).as("the first request, which makes the session of the pool: " + before.outcome()).isEqualTo(OperationState.COMPLETED);
        assertThat(before.stdout()).contains(BEFORE);

        ClientSession closedByServer;
        IOException killFailure = null;
        try (ChannelLease lease = lease()) {
            closedByServer = lease.session();
            assertThat(closedByServer.isOpen()).as("the session of the pool is open before the server ends it").isTrue();
            assertThat(exec(closedByServer, "cat /proc/$PPID/comm")).as("the parent of the shell of an exec request is the session process of sshd").contains("sshd");
            try {
                exec(closedByServer, "kill -9 $PPID");
            } catch (IOException e) {
                killFailure = e; // the channel dies with the process; whether the session really closed is asserted next
            }
            assertThat(closedByServer.waitFor(EnumSet.of(ClientSessionEvent.CLOSED), OpenSshClient.LIMIT.toMillis()))
                .as("the server ended its session process, so the client session is closed (the test never closes it); the kill command ended with " + killFailure)
                .contains(ClientSessionEvent.CLOSED);
        }

        // The next request: the pool must not hand out the closed session and must not fail; it makes one new connection and the command runs.
        Run after = run("echo " + AFTER);
        assertThat(after.outcome().verdict()).as("the request after the server closed the session: " + after.outcome()).isEqualTo(OperationState.COMPLETED);
        assertThat(after.stdout()).as("the command ran once on the new connection").contains(AFTER).doesNotContain(BEFORE);

        try (ChannelLease next = lease()) {
            assertThat(next.session()).as("the pool holds a new session, not the closed one").isNotSameAs(closedByServer);
            assertThat(next.session().isOpen()).isTrue();
        }
    }
}
