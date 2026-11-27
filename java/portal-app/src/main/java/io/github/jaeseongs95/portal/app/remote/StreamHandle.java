package io.github.jaeseongs95.portal.app.remote;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * The open binary streams of a remote command: what it writes (read from {@link #output()}) and what it reads (write to {@link #input()}). The standard error of the
 * command is read and dropped by the adapter, so it never blocks the command. The command has no terminal.
 */
public interface StreamHandle extends AutoCloseable {

    /** The standard output of the remote command. */
    InputStream output();

    /** The standard input of the remote command. Closing it sends the end of the input to the command. */
    OutputStream input();

    /**
     * Stops the command and closes the channel: the end of its input, then the signals INT, TERM and KILL spread over the stop grace of the request, then a close of the
     * channel; it waits for the end of the command to be seen. Never throws; may be called any number of times, from any thread, and every call returns the result
     * of the first.
     *
     * @return what is known of the command's end; the owner reads {@link StreamEnd#cleanup()} to know whether the command is confirmed gone
     */
    StreamEnd release();

    /** The same as {@link #release()}, for {@code try}-with-resources; the result is dropped. Use {@link #release()} when the result matters. */
    @Override
    default void close() {
        release();
    }
}
