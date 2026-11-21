package io.github.jaeseongs95.portal.ssh.sftp;

import java.io.IOException;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.channel.ClientChannel;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpErrorDataHandler;
import org.apache.sshd.sftp.client.SftpVersionSelector;
import org.apache.sshd.sftp.client.impl.DefaultSftpClient;

/**
 * MINA's SFTP client with one thing added: the channel that its constructor opened does not stay behind when the constructor fails (defect SSH_PORTAL-D-0108). The
 * constructor of {@code DefaultSftpClient} registers the channel with the session, writes the open request and waits for the answer with
 * {@code OpenFuture.verify(SFTP_CHANNEL_OPEN_TIMEOUT)}; a wait that ends by the time limit or by an interrupt throws without closing the channel, which then stays in the
 * session (and the server, which answers later, keeps a channel and the program of an elevated one). The constructor does close the channel when the SFTP initialization
 * fails, so only the wait of the open is covered here.
 *
 * <p>What is done is what {@code ChannelOpening} does for a channel whose open the owner gave up: the open is cancelled ({@code OpenFuture.cancel}), which makes MINA close
 * the channel with a close the server receives as soon as it confirms the open. The channel is not closed at once: a channel closed before the server confirmed it is
 * forgotten by the client, and the confirmation that comes later would leave the channel open on the server. The channel and its future are noted when the client's
 * channel is opened, on the thread that runs the constructor, so nothing is shared between two openings at the same time.
 */
class GuardedSftpClient extends DefaultSftpClient {

    /** What the constructor of this thread opened: the channel and the future of its open. */
    private record Opening(ClientChannel channel, OpenFuture future) {
    }

    private static final ThreadLocal<Opening> CURRENT = new ThreadLocal<>();

    /** A creation of a client that may throw after its channel was opened. */
    @FunctionalInterface
    interface Creation<T extends SftpClient> {
        T create() throws IOException;
    }

    protected GuardedSftpClient(ClientSession session) throws IOException {
        super(session, SftpVersionSelector.CURRENT, SftpErrorDataHandler.EMPTY);
    }

    /**
     * The plain client of a session: the {@code sftp} subsystem, the version negotiated as MINA's factory does ({@code SftpVersionSelector.CURRENT}).
     *
     * @param session an authenticated session
     * @return the client; the caller closes it
     * @throws IOException when the server refuses the subsystem, does not answer in time, or the connection fails
     */
    static SftpClient open(ClientSession session) throws IOException {
        return guarding(() -> new GuardedSftpClient(session));
    }

    /**
     * Runs the creation of a client of this kind; when it throws after its channel was opened, the open is given up as the class describes.
     *
     * @param creation makes the client
     * @param <T> the kind of client
     * @return the client the creation made
     * @throws IOException what the creation threw
     */
    static <T extends SftpClient> T guarding(Creation<T> creation) throws IOException {
        CURRENT.remove();
        try {
            return creation.create();
        } catch (IOException | RuntimeException e) {
            Opening opening = CURRENT.get();
            if (opening != null) {
                giveUp(opening);
            }
            throw e;
        } finally {
            CURRENT.remove();
        }
    }

    private static void giveUp(Opening opening) {
        opening.future().cancel();
        if (opening.future().isOpened()) {
            // The confirmation was handled before the cancel took effect: the channel is open and nobody holds it.
            opening.channel().close(false);
        }
    }

    @Override
    protected ChannelSubsystem createSftpChannelSubsystem(ClientSession session) {
        return new NotedChannel();
    }

    /** MINA's SFTP channel that notes the future of its open for {@link #guarding}. */
    protected class NotedChannel extends SftpChannelSubsystem {

        @Override
        public synchronized OpenFuture open() throws IOException {
            OpenFuture opening = super.open();
            CURRENT.set(new Opening(this, opening));
            return opening;
        }
    }
}
