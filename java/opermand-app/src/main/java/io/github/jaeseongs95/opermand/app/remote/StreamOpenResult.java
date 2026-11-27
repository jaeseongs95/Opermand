package io.github.jaeseongs95.opermand.app.remote;

import java.util.Objects;

/** How starting a streamed command ended. The ways it can fail to start are values of their own, as for {@link ForwardOpenResult}. */
public sealed interface StreamOpenResult permits StreamOpenResult.Opened, StreamOpenResult.Refused, StreamOpenResult.Unconfirmed {

    /** The command runs and its streams are open. */
    record Opened(StreamHandle handle) implements StreamOpenResult {

        public Opened {
            Objects.requireNonNull(handle, "handle");
        }
    }

    /**
     * The command did not start, or the owner stopped the open before the answer of the target: {@code CANCELLED} and {@code DEADLINE} after the {@code exec} request was
     * written mean that the adapter ran the stop procedure and gives no handle, not that the command is proved not to have started.
     */
    record Refused(TransferFailureCause cause) implements StreamOpenResult {

        public Refused {
            Objects.requireNonNull(cause, "cause");
        }
    }

    /** It is not known whether the command started: the session was lost after the request, or the awaited answer to it did not come. */
    record Unconfirmed(UnconfirmedCause cause) implements StreamOpenResult {

        public Unconfirmed {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
