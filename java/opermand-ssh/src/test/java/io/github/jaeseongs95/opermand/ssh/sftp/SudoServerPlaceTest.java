package io.github.jaeseongs95.opermand.ssh.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jaeseongs95.opermand.ssh.testkit.EmbeddedSshServer;
import io.github.jaeseongs95.opermand.ssh.testkit.SshTestClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where the SFTP server program of a target is looked for (defect SSH_PORTAL-D-0171). The test target has the program at a place that is in no list of the adapter's
 * defaults, and {@code sudo -n} of the test target starts an SFTP server only for the exact command line the adapter must write: the program as one quoted shell word.
 */
@Timeout(120)
class SudoServerPlaceTest {

    private static final String ODD_PLACE = "/opt/odd place/sftp-server";
    private static final String QUOTE_PLACE = "/opt/it's/sftp-server";

    @TempDir
    Path root;

    private SshTestClient client;
    private EmbeddedSshServer server;
    private ClientSession session;
    private final List<String> commands = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startTarget() throws IOException {
        for (String place : List.of(ODD_PLACE, QUOTE_PLACE)) {
            Path program = root.resolve(place.substring(1));
            Files.createDirectories(program.getParent());
            Files.writeString(program, "not a program: the test target only has to show a regular file there");
        }
        Set<String> started = Set.of("sudo -n '/opt/odd place/sftp-server'", "sudo -n '/opt/it'\\''s/sftp-server'");
        server = EmbeddedSshServer.builder().password(TestSessions.USER, TestSessions.PASSWORD).sftp(root).customize(sshd -> {
            CommandFactory original = sshd.getCommandFactory();
            SftpSubsystemFactory subsystem = new SftpSubsystemFactory.Builder().build();
            sshd.setCommandFactory((channel, command) -> {
                commands.add(command);
                return started.contains(command) ? subsystem.createSubsystem(channel) : original.createCommand(channel, command);
            });
        }).start();
        client = SshTestClient.startAcceptingAnyHostKey();
        session = client.open(server, TestSessions.USER, TestSessions.PASSWORD);
    }

    @AfterEach
    void stopTarget() throws IOException {
        session.close(true);
        client.close();
        server.close();
    }

    private SudoSftpChannels.Opening open(SftpServerPaths paths) {
        return new SudoSftpChannels(new SftpChannels(), new SftpFailureClassifier(), paths).open(session, TransferBed.TARGET);
    }

    private void assertOpened(SudoSftpChannels.Opening opening) throws IOException {
        assertThat(opening).isInstanceOf(SudoSftpChannels.Opening.Opened.class);
        try (SftpClient elevated = ((SudoSftpChannels.Opening.Opened) opening).client()) {
            assertThat(elevated.stat("/").isDirectory()).isTrue();
        }
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0171 a server program in a place that no default names is not found with the defaults: there is no server, and nothing is run")
    void theDefaultsDoNotKnowThePlace() {
        SudoSftpChannels.Opening opening = open(new DefaultSftpServerPaths());

        assertThat(opening).isInstanceOf(SudoSftpChannels.Opening.NoServer.class);
        assertThat(commands).as("no command is run for a program that was not found").isEmpty();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0171 the place an administrator named for the target is looked at before the usual ones, and the program starts under sudo -n with its path as one quoted word")
    void aNamedPlaceIsFoundAndQuoted() throws IOException {
        SudoSftpChannels.Opening opening = open(new DefaultSftpServerPaths(List.of(new SftpServerPath(ODD_PLACE))));

        assertOpened(opening);
        assertThat(commands).containsExactly("sudo -n '/opt/odd place/sftp-server'");
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0171 a place with a single quote in it cannot become code: the quote is closed, escaped and opened again")
    void aQuoteInAPlaceIsEscaped() throws IOException {
        SudoSftpChannels.Opening opening = open(target -> List.of(new SftpServerPath(QUOTE_PLACE)));

        assertOpened(opening);
        assertThat(commands).containsExactly("sudo -n '/opt/it'\\''s/sftp-server'");
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0171 the usual places stay after the named ones: the list the adapter asks is the named places first, each place once")
    void theUsualPlacesFollowTheNamedOnes() {
        SftpServerPath named = new SftpServerPath(ODD_PLACE);
        SftpServerPath usual = DefaultSftpServerPaths.USUAL_PLACES.get(1);

        List<SftpServerPath> places = new DefaultSftpServerPaths(List.of(named, usual)).candidates(TransferBed.TARGET);

        assertThat(places).startsWith(named, usual).containsAll(DefaultSftpServerPaths.USUAL_PLACES).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("SSH_PORTAL-D-0171 what is no path of a POSIX target is refused when the place is made: relative, empty, with a line break or a NUL, or very long")
    void aPlaceMustBeAnAbsolutePath() {
        for (String bad : List.of("", "sftp-server", "usr/lib/sftp-server", "/usr/lib/sftp-server\nrm -rf /", "/usr/lib/\0sftp-server", "/" + "a".repeat(600))) {
            assertThatThrownBy(() -> new SftpServerPath(bad)).as("place %s", bad.length() > 40 ? bad.substring(0, 40) : bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
