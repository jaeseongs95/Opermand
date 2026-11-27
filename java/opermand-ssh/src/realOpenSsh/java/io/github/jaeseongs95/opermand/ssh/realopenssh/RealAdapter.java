package io.github.jaeseongs95.opermand.ssh.realopenssh;

import io.github.jaeseongs95.opermand.app.remote.CredentialLookup;
import io.github.jaeseongs95.opermand.app.remote.HostKeyMode;
import io.github.jaeseongs95.opermand.app.remote.ProfileLookup;
import io.github.jaeseongs95.opermand.app.remote.RemoteTarget;
import io.github.jaeseongs95.opermand.app.remote.SecretValue;
import io.github.jaeseongs95.opermand.app.remote.SshCredential;
import io.github.jaeseongs95.opermand.app.remote.SshSettings;
import io.github.jaeseongs95.opermand.app.storage.AclPrivateStorage;
import io.github.jaeseongs95.opermand.app.storage.PosixPrivateStorage;
import io.github.jaeseongs95.opermand.domain.id.TargetId;
import io.github.jaeseongs95.opermand.domain.target.AccessMode;
import io.github.jaeseongs95.opermand.domain.target.AuthMethod;
import io.github.jaeseongs95.opermand.domain.target.ConnectionProfile;
import io.github.jaeseongs95.opermand.domain.target.ProfileAlias;
import io.github.jaeseongs95.opermand.domain.target.Setting;
import io.github.jaeseongs95.opermand.domain.target.TargetRevision;
import io.github.jaeseongs95.opermand.domain.target.TtyMode;
import io.github.jaeseongs95.opermand.ssh.exec.ChannelSignals;
import io.github.jaeseongs95.opermand.ssh.exec.MinaRemoteStreamExecutor;
import io.github.jaeseongs95.opermand.ssh.forward.MinaRemoteForwarder;
import io.github.jaeseongs95.opermand.ssh.hostkey.HostKeyEvents;
import io.github.jaeseongs95.opermand.ssh.hostkey.HostKeyRefusal;
import io.github.jaeseongs95.opermand.ssh.hostkey.ServerKeyPolicy;
import io.github.jaeseongs95.opermand.ssh.profile.PrivateFiles;
import io.github.jaeseongs95.opermand.ssh.session.ChannelOpening;
import io.github.jaeseongs95.opermand.ssh.session.MinaClientFactory;
import io.github.jaeseongs95.opermand.ssh.session.ProxyJumpResolver;
import io.github.jaeseongs95.opermand.ssh.session.SessionConnector;
import io.github.jaeseongs95.opermand.ssh.session.SessionReapSettings;
import io.github.jaeseongs95.opermand.ssh.session.SshAlgorithmPolicy;
import io.github.jaeseongs95.opermand.ssh.session.SshSessionPool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The forwarder and the stream executor of the product, wired by hand as the Spring context wires them, over the real OpenSSH server of an {@link OpenSshTarget}: key login,
 * host key checking on (strict, the known_hosts of the target), the main port of the server. Closing it closes the pool.
 */
final class RealAdapter implements AutoCloseable {

    private static final String ALIAS = "real";

    private final ThreadPoolTaskScheduler scheduler;
    private final SshSessionPool pool;
    private final MinaRemoteForwarder forwarder;
    private final MinaRemoteStreamExecutor streams;

    private RealAdapter(ThreadPoolTaskScheduler scheduler, SshSessionPool pool, MinaRemoteForwarder forwarder, MinaRemoteStreamExecutor streams) {
        this.scheduler = scheduler;
        this.pool = pool;
        this.forwarder = forwarder;
        this.streams = streams;
    }

    static RealAdapter start(OpenSshTarget target, Path root) throws IOException {
        PrivateFiles guard = new PrivateFiles(List.of(new PosixPrivateStorage(), new AclPrivateStorage()));
        Path record = root.resolve("private-state").resolve("known_hosts");
        guard.createPrivateFile(record);
        Files.writeString(record, Files.readString(target.knownHosts())); // into the private file the guard made, as the other real tests do
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
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("real-openssh-scheduler-");
        scheduler.setDaemon(true);
        scheduler.initialize();
        SimpleAsyncTaskExecutor taskExecutor = new SimpleAsyncTaskExecutor("real-openssh-");
        taskExecutor.setDaemon(true);
        SessionConnector connector = new SessionConnector(resolver, new MinaClientFactory(settings, new SshAlgorithmPolicy()), hostKeys, settings);
        SshSessionPool pool = new SshSessionPool(settings, connector, Clock.systemUTC(), taskExecutor, scheduler, SessionReapSettings.defaults());
        pool.afterPropertiesSet();
        ChannelOpening opening = new ChannelOpening(scheduler, Clock.systemUTC());
        return new RealAdapter(scheduler, pool,
            new MinaRemoteForwarder(pool, opening, scheduler, taskExecutor, Clock.systemUTC()),
            new MinaRemoteStreamExecutor(pool, opening, scheduler, Clock.systemUTC(), new ChannelSignals(), settings));
    }

    MinaRemoteForwarder forwarder() {
        return forwarder;
    }

    MinaRemoteStreamExecutor streams() {
        return streams;
    }

    /** The target of the requests: the profile of this adapter. */
    RemoteTarget remote() {
        return new RemoteTarget(new TargetId("real-openssh"), TargetRevision.of("0".repeat(64)), new ProfileAlias(ALIAS), AccessMode.READ_WRITE);
    }

    @Override
    public void close() {
        pool.destroy();
        scheduler.shutdown();
    }
}
