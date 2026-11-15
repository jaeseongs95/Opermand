package io.github.jaeseongs95.portal.ssh.sftp;

import java.io.Closeable;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place where a channel or a stream of the file transfer is closed when the close must not change the result: the result is already known (the bytes were read, the
 * channel was closed to stop the transfer, the channel only looked), so a {@code close()} that fails is the channel's own business and is logged, never thrown (defect
 * D-0013, case S-D0013-01: an exception of a close that covered the result of a read).
 *
 * <p>The JDK has no such method: {@code try}-with-resources throws the failure of {@code close()} (it is added to the primary exception as a suppressed one only when
 * there is one), which is what this class keeps from covering a result.
 */
final class ChannelClosing {

    private static final Logger LOG = LoggerFactory.getLogger(ChannelClosing.class);

    private ChannelClosing() {
    }

    /**
     * Closes the channel or stream; a failure is logged. Nothing happens for {@code null}.
     *
     * @param closeable what to close, or {@code null}
     */
    static void quietly(Closeable closeable) {
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
