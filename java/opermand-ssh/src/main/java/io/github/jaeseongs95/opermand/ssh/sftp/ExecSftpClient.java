package io.github.jaeseongs95.opermand.ssh.sftp;

import java.io.IOException;
import org.apache.sshd.client.channel.ChannelSubsystem;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.StreamingChannel;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;

/**
 * An SFTP client of MINA that speaks to a program started by an {@code exec} request instead of to the {@code sftp} subsystem: the program is the SFTP server of the target
 * run under {@code sudo -n} (stage 3 design 6.1, pilot P3-4). MINA's public factory only opens the subsystem; the one extension point it offers is
 * {@link GuardedSftpClient#createSftpChannelSubsystem}, which this class overrides with a channel whose open request is {@code exec} with the command line. Everything else
 * (the version negotiation, every operation, the pipelined streams) is MINA's own code, so an elevated transfer runs the same adapter code as a plain one.
 *
 * <p>The constructor of {@code DefaultSftpClient} opens the channel before a subclass can set a field, so the command line travels in a thread-local for the length of the
 * synchronous constructor call (the same pattern as {@link GuardedSftpClient}); nothing is shared between concurrent openings on one session. The channel of the client belongs
 * to it: closing the client closes the channel and with it the program on the target.
 */
final class ExecSftpClient extends GuardedSftpClient {

    private static final ThreadLocal<String> PENDING_COMMAND = new ThreadLocal<>();
    private static final String EXEC_REQUEST = "exec";

    private ExecSftpClient(ClientSession session) throws IOException {
        super(session);
    }

    /**
     * Starts the command on the session and speaks SFTP to it.
     *
     * @param session an authenticated session
     * @param commandLine the command line of the SFTP server (a fixed text of the portal)
     * @return the client; the caller closes it
     * @throws IOException when the program does not answer the SFTP handshake (it did not start, {@code sudo} refused) or the connection fails
     */
    static ExecSftpClient open(ClientSession session, String commandLine) throws IOException {
        String outer = PENDING_COMMAND.get();
        PENDING_COMMAND.set(commandLine);
        try {
            return guarding(() -> new ExecSftpClient(session));
        } finally {
            if (outer == null) {
                PENDING_COMMAND.remove();
            } else {
                PENDING_COMMAND.set(outer);
            }
        }
    }

    @Override
    protected ChannelSubsystem createSftpChannelSubsystem(ClientSession session) {
        return new ExecChannel(PENDING_COMMAND.get());
    }

    /** The channel of the client: MINA's own SFTP channel with another open request. */
    private final class ExecChannel extends NotedChannel {

        private final String commandLine;

        ExecChannel(String commandLine) {
            this.commandLine = commandLine;
        }

        @Override
        protected void doOpen() throws IOException {
            Session session = getSession();
            boolean wantReply = false;
            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_CHANNEL_REQUEST, EXEC_REQUEST.length() + commandLine.length() + 32);
            buffer.putUInt(getRecipient());
            buffer.putString(EXEC_REQUEST);
            buffer.putBoolean(wantReply);
            buffer.putString(commandLine);
            addPendingRequest(EXEC_REQUEST, wantReply);
            writePacket(buffer);
            setStreaming(StreamingChannel.Streaming.Async);
            asyncIn = createAsyncInput(session);
            setOut(createStdOutputStream(session));
            setErr(createErrOutputStream(session));
        }
    }
}
