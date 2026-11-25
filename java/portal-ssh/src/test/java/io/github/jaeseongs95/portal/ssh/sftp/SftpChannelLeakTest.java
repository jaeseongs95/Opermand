package io.github.jaeseongs95.portal.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.portal.app.remote.CancelSource;
import io.github.jaeseongs95.portal.app.remote.TransferFailureCause;
import io.github.jaeseongs95.portal.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.portal.ssh.testkit.SshTestClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The SFTP channel whose open the client gave up (defect SSH_PORTAL-D-0108): MINA's client constructor waits for the answer of the server to the open of the channel for
 * {@code SFTP_CHANNEL_OPEN_TIMEOUT} and throws when it runs out, without closing the channel, which the server then confirms late and keeps. The test server holds the
 * open until the test releases it, so the late confirmation is made by the test and not by a race.
 */
@Timeout(120)
class SftpChannelLeakTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @TempDir
    Path root;

    private SshTestClient client;
    private EmbeddedSshServer server;
    private ClientSession session;

    @BeforeEach
    void connect() throws IOException {
        server = EmbeddedSshServer.builder().password(TestSessions.USER, TestSessions.PASSWORD).sftp(root).start();
        client = SshTestClient.startAcceptingAnyHostKey();
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client.sshClient(), Duration.ofMillis(500));
        session = client.open(server, TestSessions.USER, TestSessions.PASSWORD);
    }

    @AfterEach
    void disconnect() throws IOException {
        session.close(true);
        client.close();
        server.close();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0108 an SFTP channel whose open timed out is closed when the server confirms it late: the server gets a close and no channel is left on the live session")
    void aTimedOutOpenLeavesNoChannel() throws Exception {
        CompletableFuture<Void> release = server.holdChannelOpens();

        assertThatThrownBy(() -> new SftpChannels().open(session)).as("the server does not answer the open within 500 ms").isInstanceOf(IOException.class);
        assertThat(session.isOpen()).as("a wait that ran out is no reason to drop a live session").isTrue();
        release.complete(null);

        assertThat(server.closedChannels().await(1, WAIT)).as("the late channel is closed by the client, with a close that the server receives").hasSize(1);
        // The session still works: a new channel opens and speaks SFTP.
        try (SftpClient later = new SftpChannels().open(session)) {
            assertThat(later.stat("/").isDirectory()).isTrue();
        }
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0107 a cancel that comes while the SFTP channel is being opened ends the wait at once: it does not run the 20 seconds of SFTP_CHANNEL_OPEN_TIMEOUT, the watch says CANCELLED, and the late channel is still closed")
    void aCancelEndsTheWaitForTheOpen() throws Exception {
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client.sshClient(), Duration.ofSeconds(20));
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        try {
            CancelSource cancel = new CancelSource();
            TransferWatch watch = new TransferWatch(scheduler, Clock.systemUTC(), Duration.ofSeconds(120), cancel);
            CompletableFuture<Void> release = server.holdChannelOpens();
            // The server has the request and holds it: the client is waiting for the answer when the cancel comes (an event, not a sleep).
            CompletableFuture<Void> cancelled = server.channelOpenRequests().whenAtLeast(1).thenAccept(requests -> cancel.cancel());
            long started = System.nanoTime();

            assertThatThrownBy(() -> new SftpChannels().open(session, watch)).isInstanceOf(IOException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the wait ended with the cancel, not with the 20 second time limit").isLessThan(Duration.ofSeconds(10));
            assertThat(cancelled).isCompleted();
            assertThat(watch.fired()).contains(TransferFailureCause.CANCELLED);
            release.complete(null);
            assertThat(server.closedChannels().await(1, WAIT)).as("the channel that the server confirms late is closed").hasSize(1);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0107 a cancel that arrives after channel open while DefaultSftpClient.init() awaits SSH_FXP_VERSION closes the channel immediately instead of waiting 20s")
    void aCancelDuringInitEndsWaitImmediatelyAndClosesChannel() throws Exception {
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client.sshClient(), Duration.ofSeconds(20));
        CompletableFuture<Void> initStarted = new CompletableFuture<>();
        SubsystemFactory silentSftp = new SubsystemFactory() {
            @Override
            public String getName() {
                return "sftp";
            }

            @Override
            public Command createSubsystem(ChannelSession channel) {
                return new Command() {
                    @Override
                    public void setInputStream(InputStream in) {
                    }

                    @Override
                    public void setOutputStream(OutputStream out) {
                    }

                    @Override
                    public void setErrorStream(OutputStream err) {
                    }

                    @Override
                    public void setExitCallback(ExitCallback callback) {
                    }

                    @Override
                    public void start(ChannelSession sessionChannel, Environment env) {
                        initStarted.complete(null);
                    }

                    @Override
                    public void destroy(ChannelSession sessionChannel) {
                    }
                };
            }
        };
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        try (EmbeddedSshServer silentServer = EmbeddedSshServer.builder()
                .password(TestSessions.USER, TestSessions.PASSWORD)
                .customize(sshd -> sshd.setSubsystemFactories(List.of(silentSftp)))
                .start();
             ClientSession silentSession = client.open(silentServer, TestSessions.USER, TestSessions.PASSWORD)) {
            CancelSource cancel = new CancelSource();
            try (TransferWatch watch = new TransferWatch(scheduler, Clock.systemUTC(), Duration.ofSeconds(120), cancel)) {
                initStarted.thenRun(cancel::cancel);
                long started = System.nanoTime();

                assertThatThrownBy(() -> new SftpChannels(Duration.ofMillis(400)).open(silentSession, watch)).isInstanceOf(IOException.class);

                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the init wait ended promptly on cancel rather than waiting 20 seconds").isLessThan(Duration.ofSeconds(5));
                assertThat(watch.fired()).contains(TransferFailureCause.CANCELLED);
                assertThat(silentServer.closedChannels().await(1, WAIT)).hasSize(1);
            }
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0107 an abandoned SFTP channel whose server never replies to open or close is force-closed locally after stop grace")
    void anUnresponsiveServerCloseIsForcedAfterGrace() throws Exception {
        SftpModuleProperties.SFTP_CHANNEL_OPEN_TIMEOUT.set(client.sshClient(), Duration.ofSeconds(20));
        CompletableFuture<Channel> created = new CompletableFuture<>();
        session.addChannelListener(new ChannelListener() {
            @Override
            public void channelInitialized(Channel channel) {
                created.complete(channel);
            }
        });
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setDaemon(true);
        scheduler.initialize();
        CompletableFuture<Void> release = server.holdChannelOpens();
        try {
            CancelSource cancel = new CancelSource();
            try (TransferWatch watch = new TransferWatch(scheduler, Clock.systemUTC(), Duration.ofSeconds(120), cancel)) {
                server.channelOpenRequests().whenAtLeast(1).thenAccept(requests -> cancel.cancel());

                assertThatThrownBy(() -> new SftpChannels(Duration.ofMillis(300)).open(session, watch)).isInstanceOf(IOException.class);

                Channel clientChannel = created.get(WAIT.toSeconds(), TimeUnit.SECONDS);
                assertThat(clientChannel.getCloseFuture().await(Duration.ofSeconds(5)))
                    .as("forceCloseAfterGrace closes the local channel after grace even while the server stays silent")
                    .isTrue();
            }
        } finally {
            release.complete(null);
            scheduler.shutdown();
        }
    }
}
