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
 * <p>The JDK has no such method (principle 2.1, the official text checked on 2026-11-15). {@code Closeable} declares {@code close()} alone and {@code close()} "throws
 * IOException" ({@code java.io.Closeable}: https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/io/Closeable.html); {@code AutoCloseable} declares
 * {@code close()} alone, "throws Exception" if the resource cannot be closed
 * (https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/AutoCloseable.html). {@code try}-with-resources does not discard the failure of {@code close()}:
 * "If the initialization of the resource completes normally, and the {@code try} block completes normally, and the automatic closing of the resource completes abruptly
 * because of a {@code throw} of a value V, then the {@code try}-with-resources statement completes abruptly because of a {@code throw} of the value V" (JLS section
 * 14.20.3.1, https://docs.oracle.com/javase/specs/jls/se25/html/jls-14.html#jls-14.20.3.1); the failure is only added to the suppressed list of the primary exception when
 * the {@code try} block threw. That is what this class keeps from covering a result. Not checked: a class-by-class search of the whole JDK API for another type that
 * closes quietly (the two interfaces above are the ones the transfer code closes through).
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
