package io.github.jaeseongs95.opermand.ssh.testkit;

import io.github.jaeseongs95.opermand.app.remote.CancelSource;
import io.github.jaeseongs95.opermand.app.remote.HostKeyMode;
import io.github.jaeseongs95.opermand.ssh.hostkey.HostKeyEvents;
import io.github.jaeseongs95.opermand.ssh.hostkey.HostKeyRefusal;
import io.github.jaeseongs95.opermand.ssh.hostkey.ServerKeyPolicy;
import io.github.jaeseongs95.opermand.ssh.profile.PrivateFiles;
import io.github.jaeseongs95.opermand.app.remote.CommandKind;
import io.github.jaeseongs95.opermand.app.remote.CredentialLookup;
import io.github.jaeseongs95.opermand.app.remote.CredentialMiss;
import io.github.jaeseongs95.opermand.app.remote.CredentialPart;
import io.github.jaeseongs95.opermand.app.remote.ExecReply;
import io.github.jaeseongs95.opermand.app.remote.ExecutionControl;
import io.github.jaeseongs95.opermand.app.remote.ExecutionLimits;
import io.github.jaeseongs95.opermand.app.remote.ForwardRequest;
import io.github.jaeseongs95.opermand.app.remote.StreamRequest;
import io.github.jaeseongs95.opermand.app.remote.PreparedCommand;
import io.github.jaeseongs95.opermand.app.remote.ProfileLookup;
import io.github.jaeseongs95.opermand.app.remote.ProfileSource;
import io.github.jaeseongs95.opermand.app.remote.RemoteExecution;
import io.github.jaeseongs95.opermand.app.remote.RemoteTarget;
import io.github.jaeseongs95.opermand.app.remote.SecretValue;
import io.github.jaeseongs95.opermand.app.remote.SshCredential;
import io.github.jaeseongs95.opermand.app.remote.SshCredentialSource;
import io.github.jaeseongs95.opermand.app.remote.SshSettings;
import io.github.jaeseongs95.opermand.app.remote.StdinBytes;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.AuthMethod;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.TargetRevision;
import io.github.jaeseongs95.opermand.domain.target.TtyMode;
import io.github.jaeseongs95.opermand.ssh.exec.ChannelSignals;
import io.github.jaeseongs95.opermand.ssh.exec.ExecOutcomes;
import io.github.jaeseongs95.opermand.ssh.exec.MinaRemoteExecutor;
import io.github.jaeseongs95.opermand.ssh.exec.MinaRemoteStreamExecutor;
import io.github.jaeseongs95.opermand.ssh.forward.MinaRemoteForwarder;
import io.github.jaeseongs95.opermand.ssh.session.ChannelOpening;
import io.github.jaeseongs95.opermand.ssh.session.MinaClientFactory;
import io.github.jaeseongs95.opermand.ssh.session.ProxyJumpResolver;
import io.github.jaeseongs95.opermand.ssh.session.SessionConnector;
import io.github.jaeseongs95.opermand.ssh.session.SshAlgorithmPolicy;
import io.github.jaeseongs95.opermand.ssh.session.SessionReapSettings;
import io.github.jaeseongs95.opermand.ssh.session.SshSessionPool;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The SSH adapter of opermand-ssh wired by hand the way the Spring context wires it, for the tests of the session pool and the executor: the real
 * {@link SshSessionPool}, {@link SessionConnector}, {@link MinaRemoteExecutor} and friends over fake profile and credential sources, a task executor and a scheduler of
 * Spring (no thread of ours), the system clock, and a host key verifier of the test's choice (everything is accepted by default). Closing it closes the pool.
 */
public final class AdapterFixture implements AutoCloseable {

    /** The profiles the test has put in: a plain map behind the {@link ProfileSource} port. */
    public static final class Profiles implements ProfileSource {
        private final Map<ProfileAlias, ConnectionProfile> profiles = new ConcurrentHashMap<>();

        public Profiles put(String alias, ConnectionProfile profile) {
            profiles.put(new ProfileAlias(alias), profile);
            return this;
        }

        @Override
        public ProfileLookup lookup(ProfileAlias alias) {
            ConnectionProfile profile = profiles.get(alias);
            return profile == null ? new ProfileLookup.NotConfigured(alias) : new ProfileLookup.Found(profile);
        }
    }

    /** The credentials the test has put in: a plain map behind the {@link SshCredentialSource} port; a profile without one is {@code Missing}. */
    public static final class Credentials implements SshCredentialSource {
        private final Map<ProfileAlias, SshCredential> credentials = new ConcurrentHashMap<>();

        public Credentials agent(String alias) {
            credentials.put(new ProfileAlias(alias), new SshCredential.Agent());
            return this;
        }

        public Credentials password(String alias, String password) {
            credentials.put(new ProfileAlias(alias), new SshCredential.Password(SecretValue.of(password)));
            return this;
        }

        @Override
        public CredentialLookup resolve(ProfileAlias alias, ConnectionProfile profile) {
            SshCredential credential = credentials.get(alias);
            return credential == null ? new CredentialLookup.Missing(List.of(new CredentialMiss(CredentialPart.PASSWORD, "test-credentials")))
                : new CredentialLookup.Resolved(credential);
        }
    }

    private final ThreadPoolTaskScheduler scheduler;
    private final SimpleAsyncTaskExecutor taskExecutor;
    private final Profiles profiles = new Profiles();
    private final Credentials credentials = new Credentials();
    private final AdjustableClock clock = new AdjustableClock();
    private final SshSessionPool pool;
    private final MinaRemoteExecutor executor;
    private final MinaRemoteForwarder forwarder;
    private final MinaRemoteStreamExecutor streams;

    /** The host key events of the fixture: nobody listens (the policy's own tests read them). */
    private static final class QuietHostKeyEvents implements HostKeyEvents {
        @Override
        public void refused(HostKeyRefusal refusal) {
            // Not needed by the session tests: the refusal is seen in the result of the connection.
        }

        @Override
        public void insecureModeActive() {
            // The fixture accepts every host key that is not pinned, on purpose.
        }
    }

    private AdapterFixture(SshSettings settings, SessionReapSettings reap, UnaryOperator<TaskExecutor> commandExecutor) {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("adapter-test-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        taskExecutor = new SimpleAsyncTaskExecutor("adapter-test-");
        taskExecutor.setDaemon(true);
        Clock clock = this.clock;
        SshAlgorithmPolicy algorithms = new SshAlgorithmPolicy();
        // The mode is insecure, so only a pinned key is checked: it is the pin of a profile that the session tests exercise (the modes are the tests of ServerKeyPolicy).
        SshSettings insecure = new SshSettings(settings.connectTimeout(), settings.heartbeatInterval(), settings.heartbeatNoReplyMax(), settings.idleProbeAfter(),
            settings.execReplyTimeout(), settings.channelPermits(), settings.consecutiveOpenRejections(), HostKeyMode.INSECURE, settings.knownHosts());
        ServerKeyPolicy hostKeys = new ServerKeyPolicy(insecure, new PrivateFiles(List.of()), new QuietHostKeyEvents());
        SessionConnector connector = new SessionConnector(new ProxyJumpResolver(profiles, credentials), new MinaClientFactory(settings, algorithms), hostKeys, settings);
        pool = new SshSessionPool(settings, connector, clock, taskExecutor, scheduler, reap);
        pool.afterPropertiesSet();
        executor = new MinaRemoteExecutor(pool, scheduler, commandExecutor.apply(taskExecutor), clock, new ChannelSignals(), settings, new ExecOutcomes());
        ChannelOpening opening = new ChannelOpening(scheduler, clock);
        forwarder = new MinaRemoteForwarder(pool, opening, scheduler, taskExecutor, clock);
        streams = new MinaRemoteStreamExecutor(pool, opening, scheduler, clock, new ChannelSignals(), settings);
    }

    /** The 2.x defaults of {@link SshSettings} with a file of known hosts that is never read (the verifier of the fixture decides). */
    public static SshSettings defaultSettings() {
        return SshSettings.defaults(Path.of("known-hosts-not-used-by-the-tests"));
    }

    /** An adapter with the given settings that accepts every host key that is not pinned. */
    public static AdapterFixture start(SshSettings settings) {
        return new AdapterFixture(settings, SessionReapSettings.defaults(), UnaryOperator.identity());
    }

    /** An adapter whose pool closes idle sessions by the given settings. */
    public static AdapterFixture start(SshSettings settings, SessionReapSettings reap) {
        return new AdapterFixture(settings, reap, UnaryOperator.identity());
    }

    /**
     * An adapter whose command executor runs its tasks (the readers of the output and the writer of the input) on the executor that {@code commandExecutor} makes from the
     * fixture's own, so that a test can make the executor reject a task. The session pool keeps the fixture's executor.
     */
    public static AdapterFixture start(SshSettings settings, UnaryOperator<TaskExecutor> commandExecutor) {
        return new AdapterFixture(settings, SessionReapSettings.defaults(), commandExecutor);
    }

    public Profiles profiles() {
        return profiles;
    }

    public Credentials credentials() {
        return credentials;
    }

    /** The clock of the pool, the executor and the timelines: the system clock that a test can move forward. */
    public AdjustableClock clock() {
        return clock;
    }

    public SshSessionPool pool() {
        return pool;
    }

    public MinaRemoteExecutor executor() {
        return executor;
    }

    public MinaRemoteForwarder forwarder() {
        return forwarder;
    }

    public MinaRemoteStreamExecutor streams() {
        return streams;
    }

    /** A request to forward a loopback port to {@code host}:{@code port} through the profile {@code alias}. */
    public static ForwardRequest forwardTo(String alias, String host, int port, ExecutionLimits limits) {
        return new ForwardRequest(target(alias), host, port, limits);
    }

    /** A request to start the command line through the profile {@code alias} with its streams open; no standard input to start with. */
    public static StreamRequest streamOf(String alias, String commandLine, ExecutionLimits limits, ExecReply reply) {
        return new StreamRequest(target(alias), new PreparedCommand(commandLine, StdinBytes.none(), CommandKind.COMMAND), limits, reply);
    }

    private static RemoteTarget target(String alias) {
        return new RemoteTarget(new TargetId("test"), TargetRevision.of("0".repeat(64)), new ProfileAlias(alias), AccessMode.READ_WRITE);
    }

    /** A password profile for a server and a user: no pinned key, no jump host, read and write, no terminal. */
    public static ConnectionProfile profileFor(EmbeddedSshServer server, String user) {
        return new ConnectionProfile(server.host(), server.port(), user, Setting.unset(), Setting.unset(), AuthMethod.PASSWORD, Setting.unset(), Setting.unset(),
            AccessMode.READ_WRITE, TtyMode.NO_TTY);
    }

    /** The same profile with a pinned host key (the fingerprint, {@code SHA256:} and base64). */
    public static ConnectionProfile profileWithPin(EmbeddedSshServer server, String user, String fingerprint) {
        return new ConnectionProfile(server.host(), server.port(), user, Setting.given(fingerprint), Setting.unset(), AuthMethod.PASSWORD, Setting.unset(), Setting.unset(),
            AccessMode.READ_WRITE, TtyMode.NO_TTY);
    }

    /** The same profile with a jump host. */
    public static ConnectionProfile profileVia(EmbeddedSshServer server, String user, String jumpAlias) {
        return new ConnectionProfile(server.host(), server.port(), user, Setting.unset(), Setting.given(new ProfileAlias(jumpAlias)), AuthMethod.PASSWORD, Setting.unset(),
            Setting.unset(), AccessMode.READ_WRITE, TtyMode.NO_TTY);
    }

    /** A request to run the command line through the profile {@code alias}, with the given limits and reply setting and no standard input. */
    public static RemoteExecution execution(String alias, String commandLine, ExecutionLimits limits, ExecReply reply) {
        return execution(alias, new PreparedCommand(commandLine, StdinBytes.none(), CommandKind.COMMAND), limits, reply);
    }

    /** A request to run a prepared command through the profile {@code alias}. */
    public static RemoteExecution execution(String alias, PreparedCommand command, ExecutionLimits limits, ExecReply reply) {
        RemoteTarget target = new RemoteTarget(new TargetId("test"), TargetRevision.of("0".repeat(64)), new ProfileAlias(alias), AccessMode.READ_WRITE);
        return new RemoteExecution(target, command, limits, reply);
    }

    /** A control that nobody cancels and whose guard allows every dispatch. */
    public static ExecutionControl control() {
        return new ExecutionControl(new CancelSource(), () -> { });
    }

    /** A control with the given cancel source and a guard that allows every dispatch. */
    public static ExecutionControl control(CancelSource cancel) {
        return new ExecutionControl(cancel, () -> { });
    }

    /** The executor of the fixture, for tests that run a request on another thread while the test thread cancels or drops connections. */
    public Executor async() {
        return taskExecutor;
    }

    @Override
    public void close() {
        pool.destroy();
        scheduler.shutdown();
    }
}
