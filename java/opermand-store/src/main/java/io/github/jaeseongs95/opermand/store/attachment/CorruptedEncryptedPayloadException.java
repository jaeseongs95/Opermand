package io.github.jaeseongs95.opermand.store.attachment;

import java.io.Serial;
import java.util.Objects;

/**
 * Raised when a chunked AEAD ({@code PAE1}) payload file has a corrupted header, truncated frame, unknown key, or failed GCM authentication tag.
 */
public final class CorruptedEncryptedPayloadException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public CorruptedEncryptedPayloadException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public CorruptedEncryptedPayloadException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        INVALID_HEADER,
        TRUNCATED_FRAME,
        UNKNOWN_KEY,
        AUTH_FAILED,
        IO_ERROR
    }
}
