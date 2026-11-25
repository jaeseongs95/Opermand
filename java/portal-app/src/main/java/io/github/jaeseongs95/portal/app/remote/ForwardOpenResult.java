package io.github.jaeseongs95.portal.app.remote;

import java.util.Objects;

/**
 * How opening a local forward ended. Each way it can fail to open is a value of its own, so the owner can tell them apart: the time limit of the open
 * ({@code Refused(DEADLINE)}), the target refusing the connection or the server refusing the channel ({@code Refused(CHANNEL_OPEN_FAILED)}), no session
 * ({@code Refused(CONNECT_FAILED)}), a session found dead before anything was sent ({@code Refused(TRANSPORT_LOST)}), the owner's cancel ({@code Refused(CANCELLED)}) and
 * a session lost while the channel was being opened ({@code Unconfirmed}). The end of a lease is the owner's own cause: it releases the handle, or cancels the open.
 */
public sealed interface ForwardOpenResult permits ForwardOpenResult.Opened, ForwardOpenResult.Refused, ForwardOpenResult.Unconfirmed {

    /** The forward is open: the channel is open and the listener accepts. */
    record Opened(ForwardHandle handle) implements ForwardOpenResult {

        public Opened {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /**
     * The forward was not opened and nothing was carried through it. A channel that the target opens after the owner gave up (a cancel or the time limit) is closed by the
     * adapter, and the session is kept.
     */
    record Refused(TransferFailureCause cause) implements ForwardOpenResult {

        public Refused {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** The session was lost after the request to open the channel was sent: it is not known whether the target connected. */
    record Unconfirmed(UnconfirmedCause cause) implements ForwardOpenResult {

        public Unconfirmed {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
