package io.github.jaeseongs95.portal.ssh.sftp;

import java.io.Closeable;
import java.io.IOException;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Opens the SFTP subsystem channel of a session and closes the channels and streams of the file transfer. It is the one place that touches MINA's
 * {@link GuardedSftpClient} (MINA's client, which does not leave its channel behind when the open fails), so a test can replace it to put a fault in front of an operation the embedded server cannot fail (a flush that is refused, a server extension
 * that is not offered). The SFTP version is negotiated by MINA: the highest both sides speak (OpenSSH's {@code sftp-server} speaks 3, which is why the adapter never uses an
 * operation that needs a newer one).
 */
@Component
public class SftpChannels {

    private static final Logger LOG = LoggerFactory.getLogger(SftpChannels.class);

    /**
     * Opens the subsystem channel on the session; the caller closes the returned client.
     *
     * @param session an authenticated session
     * @return the SFTP client
     * @throws IOException when the server refuses the subsystem or the connection fails
     */
    public SftpClient open(ClientSession session) throws IOException {
        return GuardedSftpClient.open(session);
    }

    /**
     * Closes a channel or a stream of the file transfer when the close must not change the result: the result is already known (the bytes were read, the channel was closed
     * to stop the transfer, the channel only looked), so a {@code close()} that fails is the channel's own business and is logged, never thrown (defect D-0013, case
     * S-D0013-01: an exception of a close that covered the result of a read). Nothing happens for {@code null}.
     *
     * <p>The JDK has no such method (principle 2.1, the official text checked on 2026-11-15). {@code Closeable} declares {@code close()} alone and {@code close()} "throws
     * IOException" ({@code java.io.Closeable}: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/Closeable.html); {@code AutoCloseable} declares
     * {@code close()} alone, "throws Exception" if the resource cannot be closed
     * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/AutoCloseable.html). {@code try}-with-resources does not discard the failure of {@code close()}:
     * "If the initialization of the resource completes normally, and the {@code try} block completes normally, and the automatic closing of the resource completes abruptly
     * because of a {@code throw} of a value V, then the {@code try}-with-resources statement completes abruptly because of a {@code throw} of the value V" (JLS section
     * 14.20.3.1, https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.20.3.1); the failure is only added to the suppressed list of the primary exception when
     * the {@code try} block threw. That is what this method keeps from covering a result. Not checked: a class-by-class search of the whole JDK API for another type that
     * closes quietly (the two interfaces above are the ones the transfer code closes through).
     *
     * @param closeable what to close, or {@code null}
     */
    public void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            LOG.debug("A channel or stream did not close cleanly; the result it carried stands", e);
        }
    }
}
