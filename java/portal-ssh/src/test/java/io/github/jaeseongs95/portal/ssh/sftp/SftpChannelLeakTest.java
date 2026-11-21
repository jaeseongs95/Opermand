package io.github.jaeseongs95.portal.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.portal.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.portal.ssh.testkit.SshTestClient;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.SftpModuleProperties;
import org.apache.sshd.sftp.client.SftpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

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
}
