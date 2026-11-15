package io.github.jaeseongs95.portal.ssh.realopenssh;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.Pattern;
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
 * <p><b>How the server closes the session (both platforms, one method).</b> The CI jobs run the OpenSSH server on the same machine as the test JVM ({@code java/ci/openssh-linux.sh},
 * {@code java/ci/openssh-windows.cmd}: a second {@code sshd} of its own with the configuration folder {@code portal-openssh-ci}, on a loopback port). The test finds that listener
 * with {@link ProcessHandle#allProcesses()} and ends every descendant of it with {@link ProcessHandle#destroyForcibly()}; the listener stays, so the next connection is accepted.
 * No external program is started and no command is sent to the server. The processes that hold the connection are the descendants of the listener: on a POSIX {@code sshd} the
 * listener forks one process per connection (the monitor, {@code sshd: user [priv]}, or {@code sshd-session} from OpenSSH 9.8), which forks the unprivileged child of the
 * session ({@code sshd: user@notty}) that starts the shell of an exec request; on Win32-OpenSSH the listener starts a {@code sshd.exe} per connection, which starts the session
 * process and the shell. The TCP connection closes when no process holds its socket, so all descendants are ended, not one. They are the only connections of this server, because
 * the tests of the task run one after another and close their sessions. The server then closes the connection without an SSH disconnect message, which is what a client sees
 * when a server, a restart or a firewall ends a session. If no listener or no session process is found, or the operating system refuses to end one (the test JVM needs the right
 * to signal the processes of the server), the test fails with that fact. It is not skipped on either platform. Nothing the product does closes the session: the test never
 * calls close on it.
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

    /** The executable of an sshd (Linux {@code sshd}, Windows {@code sshd.exe}) as the first word of a command line, then the configuration folder of the CI preparation script. */
    private static final Pattern PREPARED_LISTENER = Pattern.compile("^\"?(\\S*[/\\\\])?sshd(\\.exe)?\"?\\s.*portal-openssh-ci", Pattern.DOTALL);

    private static boolean namesSshd(ProcessHandle process) {
        return process.info().command().map(command -> {
            String name = Path.of(command).getFileName().toString();
            return name.equalsIgnoreCase("sshd") || name.equalsIgnoreCase("sshd.exe");
        }).orElse(false);
    }

    /**
     * The listener of the server that the CI preparation script started: an sshd whose command line names the configuration folder {@code portal-openssh-ci}. When the operating
     * system does not give the command line of a process of another account (a SYSTEM process on Windows), the listener is the sshd that no sshd started.
     */
    private static boolean isPreparedListener(ProcessHandle process) {
        return process.info().commandLine()
            .map(line -> PREPARED_LISTENER.matcher(line).find())
            .orElseGet(() -> namesSshd(process) && process.parent().map(parent -> !namesSshd(parent)).orElse(true));
    }

    /** The server side ends the session: every process of a connection of the prepared listener is ended; what was done, as text for the failure message. */
    private static String endTheSessionOnTheServer() {
        List<ProcessHandle> listeners = ProcessHandle.allProcesses().filter(OpenSshSessionPoolTest::isPreparedListener).toList();
        assertThat(listeners).as("the listener of the sshd that the CI preparation script started on this machine").hasSize(1);
        List<ProcessHandle> connections = listeners.get(0).descendants().toList();
        assertThat(connections).as("the processes of the connections of the listener (pid " + listeners.get(0).pid() + ")").isNotEmpty();
        long refused = connections.stream().filter(process -> !process.destroyForcibly()).count();
        return "ended " + connections.size() + " process(es) of the connections of the sshd listener (pid " + listeners.get(0).pid() + ") with ProcessHandle.destroyForcibly, "
            + refused + " refused";
    }

    @Test
    @DisplayName("S-D0038-01 (cause of D-0038 not confirmed; this fixes the category) a real sshd ends the session of the pool: the next request through the pool makes one new connection and runs once")
    void aRequestAfterTheServerClosedTheSession() throws Exception {
        startAdapter();

        Run before = run("echo " + BEFORE);
        assertThat(before.outcome().verdict()).as("the first request, which makes the session of the pool: " + before.outcome()).isEqualTo(OperationState.COMPLETED);
        assertThat(before.stdout()).contains(BEFORE);

        ClientSession closedByServer;
        try (ChannelLease lease = lease()) {
            closedByServer = lease.session();
            assertThat(closedByServer.isOpen()).as("the session of the pool is open before the server ends it").isTrue();
            String how = endTheSessionOnTheServer();
            assertThat(closedByServer.waitFor(EnumSet.of(ClientSessionEvent.CLOSED), OpenSshClient.LIMIT.toMillis()))
                .as("the server ended its session process, so the client session is closed (the test never closes it); the server side did: " + how)
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
