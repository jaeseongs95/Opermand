package io.github.jaeseongs95.portal.app.remote;

import io.github.jaeseongs95.portal.domain.operation.CleanupState;

/**
 * An open local forward: a listener on the loopback address of this machine that carries the first connection made to it through the target's SSH session to the
 * host and port of the {@link ForwardRequest}. The owner (the desktop use case) connects its client (a native RDP client, an RFB reader) to {@link #localPort()} and
 * calls {@link #release()} when the client is done or its lease ends.
 *
 * <p>One forward carries one connection: the listener accepts the first client, closes itself, and the connection lives until either end closes it. A forward that
 * carried its connection stays until it is released, because the owner is the one who knows that the lease is over.
 */
public interface ForwardHandle extends AutoCloseable {

    /** The port of the listener on the loopback address; chosen by the system, so it differs from forward to forward. */
    int localPort();

    /**
     * Closes the listener and the channel and waits until both are confirmed closed, or until the stop grace of the request has passed. Never throws; may be called any
     * number of times, from any thread, and every call returns the result of the first.
     *
     * @return {@link CleanupState#COMPLETE} when the listener, the channel and the threads that served them are confirmed closed (a session that was lost closed them
     *     as well), {@link CleanupState#INCOMPLETE} when the close of the channel was not confirmed in time or a thread did not end: the owner must not treat the
     *     forward as gone
     */
    CleanupState release();

    /** The same as {@link #release()}, for {@code try}-with-resources; the result is dropped. Use {@link #release()} when the result matters. */
    @Override
    default void close() {
        release();
    }
}
